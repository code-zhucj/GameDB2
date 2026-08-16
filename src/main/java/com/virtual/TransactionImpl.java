package com.virtual;

import com.virtual.Log.Log;
import com.virtual.api.TableConfig;
import com.virtual.entity.Entity;
import com.virtual.exception.GameDBException;
import com.virtual.exception.RetryException;
import com.virtual.persistent.Persistent;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;

/**
 * 事物处理的具体实现
 * todo 事物需要补充监控 事务数量、重试率、快照耗时、Flash 吞吐量、队列深度 （MBean 实现）
 */
@Slf4j
public final class TransactionImpl implements Transaction, AutoCloseable {

    private static final AtomicLong TRANSACTION_NUMS = new AtomicLong(0);
    private static final AtomicLong TOTAL_SUBMIT = new AtomicLong(0);
    private static final AtomicLong RETRY_COUNT = new AtomicLong(0);
    private static final int THREAD_NUM = GameDB.getConfig().getTransactionThreadNum();
    @Setter
    private static volatile boolean reject = false;
    public static ThreadPoolExecutor TRANSACTION_POOL = new ThreadPoolExecutor(THREAD_NUM, THREAD_NUM, 0, TimeUnit.MICROSECONDS, new LinkedBlockingDeque<>(GameDB.getConfig().getMaxTransactionCount()), new ThreadFactory() {
        private static final AtomicInteger id = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "transaction-" + id.getAndIncrement());
        }
    });
    private static final SafeThread SAFE = new SafeThread() {{
        start();
    }};

    private static final ThreadLocal<TransactionImpl> CURRENT = new ThreadLocal<>();
    /**
     * 事物列表，当子事物logic处理完时，执行commit不会真的提交，只有在根事物commit时才一并提交，但是回滚则直接丢弃修改日志
     */
    private final List<TransactionImpl> transactions = new ArrayList<>();
    private final Map<Entity, Map<String, Log<?>>> logs = new HashMap<>();

    private final List<Runnable> rollbackTask = new ArrayList<>();
    private final List<Runnable> commitTask = new ArrayList<>();

    private final TreeMap<LockKey, Record<?>> records = new TreeMap<>();
    private int retryNum = 0;

    public static TransactionImpl checkAndGet() {
        TransactionImpl transaction = CURRENT.get();
        if (transaction == null) {
            throw new RuntimeException("当前非事物环境");
        }
        return transaction.transactions.getLast();
    }

    public static TransactionImpl getOrCreate() {
        TransactionImpl transaction = CURRENT.get();
        if (transaction == null) {
            CURRENT.set(transaction = new TransactionImpl());
            transaction.transactions.add(transaction);
        } else {
            transaction = transaction.transactions.getLast();
        }
        return transaction;
    }

    public static boolean existLogic() {
        return TRANSACTION_NUMS.get() > 0;
    }

    public static long getActiveTransactionCount() {
        return TRANSACTION_NUMS.get();
    }

    public static long getTotalTransactionCount() {
        return TOTAL_SUBMIT.get();
    }

    public static long getRetryCount() {
        return RETRY_COUNT.get();
    }

    public static int getTransactionQueueDepth() {
        return TRANSACTION_POOL.getQueue().size();
    }

    /**
     * submit 提交一个事物,规则是需要在非事物环境提交,如果当前已在事物环境,则分两种情况
     * 1：需要立即执行，那么使用{@link TransactionImpl#execute(Logic)}
     * 2: 希望异步新事物中执行: 则考虑使用{@link TransactionImpl#addCommitTask(Runnable)} 或 {@link TransactionImpl#addRollbackTask(Runnable)}
     *
     * @param logic
     * @return
     */
    public static Future<?> submit(Logic logic) {
        TransactionImpl transaction = CURRENT.get();
        if (transaction != null) {
            log.error("{} 只允许在非事物环境提交", logic.getClass().getName());
            throw new GameDBException();
        }
        TRANSACTION_NUMS.incrementAndGet();
        TOTAL_SUBMIT.incrementAndGet();
        return TRANSACTION_POOL.submit(new LogicFuture(logic));
    }

    /**
     * 加入到当前事物中，只允许在事物环境下运行
     */
    public static Logic.State execute(Logic logic) {
        checkAndGet();
        LogicFuture logicFuture = new LogicFuture(logic);
        logicFuture.run();
        return logicFuture.result;
    }

    public void log(Entity entity, String field, Log<?> log) {
        logs.computeIfAbsent(entity, _ -> new HashMap<>()).put(field, log);
        markModify();
    }

    @Override
    public void commit() {
        // 对于子事物，将所有的提交日志merge到父日志中
        TransactionImpl transaction = transactions.removeLast();
        if (transactions.isEmpty()) {
            try {
                // 空了,真正开始提交,那么此时主要开始遍历所有涉及到的修改，先循环锁定再慢慢改
                if (!checkAndLock()) {
                    throw new RetryException();
                }
                Set<Entity> entities = HashSet.newHashSet(logs.size());
                logs.keySet().forEach(entity -> entities.add(entity.getRoot()));
                Map<LockKey, Record<?>> transactionPack = HashMap.newHashMap(entities.size());
                // 先设置版本号
                boolean immediate = false;
                for (Map.Entry<LockKey, Record<?>> entry : records.entrySet()) {
                    Record<?> copy = entry.getValue();
                    if (!immediate && copy.getTable().getTableConfig().immediate()) {
                        immediate = true;
                    }
                    if (copy.getState() == Record.State.DELETE) {
                        copy.setPersistent(Record.State.DELETE);
                        // 重新put一个空的进去表示库中已经没有,避免该记录处于未Flash状态但内存已不存在,而另一线程从库中读到旧数据的问题
                        copy.getTable().putRecord(new Record<>(Record.State.NULL, copy.getTable(), copy.getPrimaryKey()));
                        if (copy.getTable().getTableConfig().type() == TableConfig.Type.DB) {
                            transactionPack.put(entry.getKey(), copy);
                        }
                    } else if (entities.remove(copy.getEntity())
                            || copy.getState() == Record.State.UPDATE
                            || copy.getState() == Record.State.INSERT) {
                        copy.setVersion(copy.getVersion() + 1);
                        copy.setPersistent(copy.getState());
                        copy.setState(Record.State.DB);
                        copy.getTable().putRecord(copy);
                        if (copy.getTable().getTableConfig().type() == TableConfig.Type.DB) {
                            transactionPack.put(entry.getKey(), copy);
                        }
                    }
                }
                // 所有的record 都修改完成了,如果entities中依然不为空,则存在非法访问的entity
                if (!entities.isEmpty()) {
                    throw new GameDBException("非法访问entity");
                }
                if (!transactionPack.isEmpty()) {
                    // 这里只会有一次锁竞争
                    Persistent.INSTANCE.getSnapshot().onChanged(transactionPack);
                    if (immediate) {
                        Persistent.INSTANCE.getSnapshot().triggerImmediate();
                    }
                }
                // 再修改entity
                logs.values().stream().flatMap(v -> v.values().stream()).forEach(Transaction::commit);
                CURRENT.remove();
                transaction.commitTask.forEach(Runnable::run);
            } finally {
                releaseLock();
            }
        } else {
            TransactionImpl father = transactions.getLast();
            transaction.logs.forEach((k, v) -> {
                if (father.logs.containsKey(k)) {
                    father.logs.get(k).putAll(v);
                } else {
                    father.logs.put(k, v);
                }
            });
            father.commitTask.addAll(transaction.commitTask);
        }
    }

    @SuppressWarnings("unchecked")
    <T extends TableDefine> Record<T> getRecord(LockKey lockKey) {
        return (Record<T>) records.get(lockKey);
    }

    private boolean visitValidVersion() {
        for (Record<?> recordCopy : records.values()) {
            Table<? extends TableDefine> table = recordCopy.getTable();
            Record<? extends TableDefine> record = table.getRecord(recordCopy.getPrimaryKey());
            if (record != null && record.getVersion() != recordCopy.getVersion()) {
                return false;
            }
        }
        return true;
    }

    private boolean checkAndLock() {
        List<Lock> queue = new ArrayList<>(records.size());
        for (Record<?> recordCopy : records.values()) {
            Lock lock = recordCopy.getRowLock().writeLock();
            lock.lock();
            queue.add(lock);
            Table<? extends TableDefine> table = recordCopy.getTable();
            Record<? extends TableDefine> record = table.getRecord(recordCopy.getPrimaryKey());
            if (record != null && record.getVersion() != recordCopy.getVersion()) {
                // 版本号对不上了,释放之前所有的锁
                for (int i = queue.size() - 1; i >= 0; i--) {
                    queue.get(i).unlock();
                }
                return false;
            }
        }
        return true;
    }

    private void releaseLock() {
        for (Record<?> value : records.values()) {
            value.getRowLock().writeLock().unlock();
        }
    }

    @Override
    public void rollback() {
        transactions.removeLast().rollbackTask.forEach(Runnable::run); // 直接丢弃
        if (transactions.isEmpty()) {
            CURRENT.remove();
        }
    }

    /**
     * 添加事物提交后任务，在事物完成提交后执行列表中的任务,注意:执行该任务时是不在事物环境中执行的
     */
    public void addCommitTask(Runnable r) {
        commitTask.add(r);
    }

    /**
     * 添加事物回滚后任务，在事物完成回滚后执行列表中的任务,注意:执行回滚任务时,如果是子事物回滚,则运行环境是在父事物中的,如果是父事物回滚,则运行环境是无事物的
     */
    public void addRollbackTask(Runnable r) {
        rollbackTask.add(r);
    }

    @SuppressWarnings("unchecked")
    public <T> Log<T> getLog(Entity e, String fieldName) {
        Map<String, Log<?>> entityFieldLog = logs.get(e);
        if (entityFieldLog == null) {
            return null;
        }
        return (Log<T>) entityFieldLog.get(fieldName);
    }

    public void recorded(LockKey lockKey, Record<?> record) {
        records.put(lockKey, record);
    }

    private void retry() {
        transactions.clear();
        transactions.add(this);
        commitTask.clear();
        rollbackTask.clear();
        logs.clear();

        for (Map.Entry<LockKey, Record<?>> entry : records.entrySet()) {
            Record<?> copy = entry.getValue();
            copy.getRowLock().writeLock().lock();
            Table<? extends TableDefine> table = copy.getTable();
            Record<? extends TableDefine> newCopy = table.getRecord(copy.getPrimaryKey()).copy();
            newCopy.bindLock(copy.getRowLock());
            entry.setValue(newCopy);
        }
    }

    private boolean isFinalTransaction() {
        return transactions.size() <= 1;
    }

    public void markModify() {
        if (reject) {
            throw new GameDBException("服务器繁忙,暂时无法提交修改");
        }
    }

    @Override
    public void close() throws Exception {
        TRANSACTION_POOL.close();
    }

    private static class LogicFuture implements Runnable {

        private final Logic logic;
        private volatile Logic.State result;
        private volatile long startTime;

        public LogicFuture(Logic logic) {
            this.logic = logic;
        }

        @Override
        public void run() {
            result = null;
            startTime = System.nanoTime();
            SAFE.exec.put(Thread.currentThread(), this);
            TransactionImpl transaction = TransactionImpl.getOrCreate();
            try {
                result = logic.process();
                SAFE.exec.remove(Thread.currentThread());
                if (result == Logic.State.SUCCESS) {
                    if (transaction.isFinalTransaction()
                            && GameDB.getConfig().isDoubleExec()
                            && transaction.retryNum == 0) {
                        result = Logic.State.RETRY;
                    } else {
                        transaction.commit();
                        TRANSACTION_NUMS.decrementAndGet();
                        return;
                    }
                }
            } catch (RetryException e) {
                result = Logic.State.RETRY;
                log.warn("事物失败,触发重试", e);
            } catch (GameDBException e) {
                result = Logic.State.EXCEPTION;
                log.error("事物异常", e);
            } catch (Throwable e) {
                // 此时需要检查一下，是否是版本号是否变化，如果发生变化依然走重试逻辑，因为异常有可能是脏读导致的
                if (transaction.visitValidVersion()) {
                    result = Logic.State.EXCEPTION;
                    log.error("事物异常", e);
                } else {
                    result = Logic.State.RETRY;
                }
            }

            if (result == Logic.State.RETRY) {
                if (transaction.retryNum++ >= GameDB.getConfig().getRetryNum()) {
                    log.error("重试{}次未成功, 异常Logic {}", GameDB.getConfig().getRetryNum(), logic.getClass().getName());
                    transaction.rollback(); // 这里看实际业务需求看是不是可以执行回滚任务
                    TRANSACTION_NUMS.decrementAndGet();
                    return;
                }
                log.debug("事物重试 {}", this.logic.getClass().getName());
                RETRY_COUNT.incrementAndGet();
                transaction.retry();
                run();
                transaction.releaseLock();
            } else {
                transaction.rollback();
                TRANSACTION_NUMS.decrementAndGet();
            }
        }
    }


    private static class SafeThread extends Thread {

        private final Map<Thread, LogicFuture> exec = new ConcurrentHashMap<>();

        private SafeThread() {
            super("SafeThread");
            setDaemon(true);
        }

        @Override
        public void run() {
            while (!TRANSACTION_POOL.isTerminated()) {
                long now = System.nanoTime();
                for (Map.Entry<Thread, LogicFuture> entry : exec.entrySet()) {
                    LogicFuture logicFuture = entry.getValue();
                    long costTime = now - logicFuture.startTime;
                    if (logicFuture.result == null && costTime >= TimeUnit.MILLISECONDS.toNanos(GameDB.getConfig().getLogicTimeout())) {
                        log.error("线程 {} 执行 {} 超时, 耗时 {}ms", entry.getKey().getName(),
                                entry.getValue().logic.getClass().getName(), TimeUnit.NANOSECONDS.toMillis(costTime));
                    }
                }
                LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(5));
            }
        }
    }


}

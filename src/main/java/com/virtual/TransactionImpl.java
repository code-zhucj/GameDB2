package com.virtual;

import com.virtual.Log.Log;
import com.virtual.entity.Entity;
import com.virtual.persistent.Persistent;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;

/**
 * 事物处理的具体实现
 */
@Slf4j
public final class TransactionImpl implements Transaction {

    private static final int THREAD_NUM = 10;
    public static ThreadPoolExecutor TRANSACTION_POOL = new ThreadPoolExecutor(THREAD_NUM, THREAD_NUM, 0, TimeUnit.MICROSECONDS, new LinkedBlockingDeque<>(), new ThreadFactory() {
        private static final AtomicInteger id = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "transaction-" + id.getAndIncrement());
        }
    });

    private static final ThreadLocal<TransactionImpl> CURRENT = new ThreadLocal<>();
    /**
     * 事物列表，当子事物logic处理完时，执行commit不会真的提交，只有在根事物commit时才一并提交，但是回滚则直接丢弃修改日志
     */
    private final List<TransactionImpl> transactions = new ArrayList<>();
    private final Map<Entity, Map<String, Log<?>>> logs = new HashMap<>();

    private final List<Runnable> rollbackTask = new ArrayList<>();
    private final List<Runnable> commitTask = new ArrayList<>();

    private final TreeMap<LockKey, Record<?>> records = new TreeMap<>();
    private int retryNum = 3; // todo 开放配置


    public static TransactionImpl checkAndGet() {
        TransactionImpl transaction = CURRENT.get();
        if (transaction == null) {
            throw new RuntimeException("当前非事物环境");
        }
        // todo 不是说事物不为空就行，比如事物回滚时执行回滚任务，此时事物还在，但时已经不算处于事物中了，这个得处理一下
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
        return !TRANSACTION_POOL.getQueue().isEmpty();
    }

    public static Future<?> submit(Logic logic) {
        return TRANSACTION_POOL.submit(new LogicFuture(logic));
    }

    public void log(Entity entity, String field, Log<?> log) {
        logs.computeIfAbsent(entity, _ -> new HashMap<>()).put(field, log);
    }

    @Override
    public void commit() {
        // 对于子事物，将所有的提交日志merge到父日志中
        TransactionImpl transaction = transactions.removeLast();
        if (transactions.isEmpty()) {
            // 空了,真正开始提交,那么此时主要开始遍历所有涉及到的修改，先循环锁定再慢慢改
            if (!checkAndLock()) {
                throw new GameDBException(); // todo 这里先抛异常
            }
            Set<Entity> entities = HashSet.newHashSet(logs.size());
            logs.keySet().forEach(entity -> entities.add(entity.getRoot()));
            Map<LockKey, Record<?>> transactionPack = HashMap.newHashMap(entities.size());
            // 先设置版本号
            for (Map.Entry<LockKey, Record<?>> entry : records.entrySet()) {
                Record<?> copy = entry.getValue();
                if (entities.contains(copy.getEntity())) {
                    copy.setVersion(copy.getVersion() + 1);
                    copy.setPersistent(copy.getState());
                    copy.setState(Record.State.DB);
                    copy.getTable().putRecord(copy);
                    transactionPack.put(entry.getKey(), copy);
                }
            }
            // 这里只会有一次锁竞争
            Persistent.INSTANCE.getSnapshot().onChanged(transactionPack);
            // 再修改entity
            logs.values().stream().flatMap(v -> v.values().stream()).forEach(Transaction::commit);

            releaseLock();
            CURRENT.remove();
            transaction.commitTask.forEach(Runnable::run);
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

    public void addCommitTask(Runnable r) {
        commitTask.add(r);
    }

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

    private static class LogicFuture implements Runnable {

        private Logic logic;

        public LogicFuture(Logic logic) {
            this.logic = logic;
        }

        @Override
        public void run() {
            TransactionImpl transaction = TransactionImpl.getOrCreate();
            Logic.State result;
            try {
                result = logic.process();
                if (result == Logic.State.SUCCESS) {
                    transaction.commit();
                    return;
                }
            } catch (GameDBException e) {
                result = Logic.State.RETRY;
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
                if (transaction.retryNum-- <= 0) {
                    log.error("重试{}次未成功, 异常Logic {}", 3, logic.getClass().getName());
                    transaction.rollback(); // 这里看实际业务需求看是不是可以执行回滚任务
                    return;
                }
                log.debug("事物重试 {}", this.logic.getClass().getName());
                transaction.retry();
                run();
                transaction.releaseLock();
            } else {
                transaction.rollback();
            }
        }
    }


}

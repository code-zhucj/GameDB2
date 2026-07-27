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

/**
 * 事物处理的具体实现
 */
@Slf4j
public final class TransactionImpl implements Transaction {

    private static ThreadPoolExecutor TRANSACTION_POOL = new ThreadPoolExecutor(10, 10, 0, TimeUnit.MICROSECONDS, new LinkedBlockingDeque<>(), new ThreadFactory() {
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
        return TRANSACTION_POOL.submit(new LogicFuture<>(logic));
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
            Set<Entity> entities = new HashSet<>();
            logs.keySet().forEach(entity -> entities.add(entity.getRoot()));
            // 先设置版本号
            for (Map.Entry<LockKey, Record<?>> entry : records.entrySet()) {
                Record<?> copy = entry.getValue();
                if (entities.contains(copy.getEntity())) {
                    copy.setVersion(copy.getVersion() + 1);
                    copy.getTable().putRecord(copy);
                    //todo 这里是直接序列化还是打标记，感觉打标记比较好，这里当前是写锁，并发不好，异步出去用读锁序列化可能效率更高
                    Persistent.INSTANCE.getSnapshot().onChanged(entry.getKey(), copy);
                }
            }
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
    <T extends TableDefine<?>> Record<T> getRecord(LockKey lockKey) {
        return (Record<T>) records.get(lockKey);
    }

    private boolean visitValidVersion() {
        for (Record<?> recordCopy : records.values()) {
            Table<? extends TableDefine<?>> table = recordCopy.getTable();
            Record<? extends TableDefine<?>> record = table.getRecord(recordCopy.getPrimaryKey());
            if (record != null && record.getVersion() != recordCopy.getVersion()) {
                return false;
            }
        }
        return true;
    }

    private boolean checkAndLock() {
        List<LockKey> queue = new ArrayList<>(records.size());
        for (LockKey lockKey : records.keySet()) {
            lockKey.writeLock();
            queue.add(lockKey);
            Record<TableDefine<?>> recordCopy = getRecord(lockKey);
            Table<? extends TableDefine<?>> table = recordCopy.getTable();
            Record<? extends TableDefine<?>> record = table.getRecord(recordCopy.getPrimaryKey());
            if (record != null && record.getVersion() != recordCopy.getVersion()) {
                // 版本号对不上了,释放之前所有的锁
                for (int i = queue.size() - 1; i >= 0; i--) {
                    queue.get(i).writUnlock();
                }
                return false;
            }
        }
        return true;
    }

    private void releaseLock() {
        for (LockKey lockKey : records.keySet()) {
            lockKey.writUnlock();
        }
    }

    @Override
    public void rollback() {
        transactions.removeLast().rollbackTask.forEach(Runnable::run); // 直接丢弃
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

    public void recorded(Record<?> record) {
        Table<? extends TableDefine<?>> table = record.getTable();
        records.put(Locks.getOrCreateLockKey(table.getTableName(), record.getPrimaryKey()), record);
    }

    private void retry() {
        transactions.clear();
        transactions.add(this);
        commitTask.clear();
        rollbackTask.clear();
        logs.clear();

        for (Map.Entry<LockKey, Record<?>> entry : records.entrySet()) {
            entry.getKey().writeLock();
            Record<?> copy = entry.getValue();
            Table<? extends TableDefine<?>> table = copy.getTable();
            Record<? extends TableDefine<?>> record = table.getRecord(copy.getPrimaryKey());
            entry.setValue(record);
        }
    }

    private static class LogicFuture<R> implements Future<R>, Runnable {

        private Logic logic;

        public LogicFuture(Logic logic) {
            this.logic = logic;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public R get() throws InterruptedException, ExecutionException {
            return null;
        }

        @Override
        public R get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return null;
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

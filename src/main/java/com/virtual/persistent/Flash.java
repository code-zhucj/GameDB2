package com.virtual.persistent;

import com.virtual.LockKey;
import com.virtual.TableDefine;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.locks.LockSupport;

/**
 * @author zhuchuanji
 * @Description 刷库线程
 * @Create: 2026/5/31 6:44
 */
@Slf4j
public class Flash extends Thread {

    private final LinkedBlockingDeque<Map<LockKey, Operation>> tasks = new LinkedBlockingDeque<>();
    private volatile boolean ending = false;

    private boolean retry = false;
    private long retryStartTime = 0;

    public Flash() {
        super("Flash");
    }

    @Override
    public void run() {
        Snapshot snapshot = Persistent.INSTANCE.getSnapshot();
        PersistentClient client = Persistent.INSTANCE.getPersistentClient();
        while (!snapshot.isEnd() || !tasks.isEmpty()) {
            Map<LockKey, Operation> poll = tasks.pollFirst();
            if (poll == null) {
                continue;
            }
            long startTime = System.currentTimeMillis();
            log.info("flash 处理数据量 {}", poll.size());
            try {
                client.startTransaction();
                Map<String, List<BatchOp>> byTable = groupByTable(poll);
                Map<String, Class<?>> classByTable = classByTable(poll);
                // 逐表批量落库
                for (Map.Entry<String, List<BatchOp>> group : byTable.entrySet()) {
                    String tableName = group.getKey();
                    Class<?> tableClass = classByTable.get(tableName);
                    @SuppressWarnings("unchecked")
                    TableHelper<?> helper = Persistent.INSTANCE.getTableHelper(tableName, (Class<? extends TableDefine>) tableClass);
                    helper.batchWrite(group.getValue());
                }
                client.commitTransaction();
                log.info("flash 处理数据量 {} 完成, 耗时 {} ms", poll.size(), System.currentTimeMillis() - startTime);
                retry = false;
            } catch (Exception e) {
                log.error("Flash 落库异常，回滚事务！", e);
                try {
                    client.abortTransaction();
                } catch (Exception ex) {
                    log.error("事务回滚失败", ex);
                }
                log.info("失败事物重新入队重试");

                if (!retry) {
                    retry = true;
                    retryStartTime = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - retryStartTime >= 120_000) {
                    log.error("严重异常,Flash 重试超过2分钟,GameDB将停止工作,请检查数据库是否正常");
                    System.exit(0);
                    break;
                } else if (System.currentTimeMillis() - retryStartTime >= 60_000) {
                    log.error("严重异常,Flash 重试超过1分钟,请检查数据库是否正常");
                }

                LockSupport.parkNanos(1_000_000_000L); // 退避 1 秒，避免 DB 宕机时空转
                tasks.offerFirst(poll); // 插回队头，保证事务处理顺序
            }
        }
        ending = true;
    }


    public void addTask(Map<LockKey, Operation> snapshot) {
        tasks.offerLast(snapshot);
    }

    public boolean isEnd() {
        return ending;
    }

    /**
     * 按表名分组，将 Map<LockKey, Operation> 转化为每个表对应的 BatchOp 列表
     */
    Map<String, List<BatchOp>> groupByTable(Map<LockKey, Operation> poll) {
        Map<String, List<BatchOp>> byTable = new HashMap<>();
        for (Map.Entry<LockKey, Operation> entry : poll.entrySet()) {
            Operation op = entry.getValue();
            String tableName = op.t().getTableName();
            Comparable<?> key = entry.getKey().id();
            BatchOpType type = switch (op.s()) {
                case DELETE -> BatchOpType.DELETE;
                case INSERT -> BatchOpType.INSERT;
                default -> BatchOpType.UPDATE;
            };
            byTable.computeIfAbsent(tableName, k -> new ArrayList<>())
                    .add(new BatchOp(type, key, op.w()));
        }
        return byTable;
    }

    /**
     * 从任务中提取表名到 Class 的映射
     */
    Map<String, Class<?>> classByTable(Map<LockKey, Operation> poll) {
        Map<String, Class<?>> classByTable = new HashMap<>();
        for (Operation op : poll.values()) {
            classByTable.putIfAbsent(op.t().getTableName(), op.t().getTableClass());
        }
        return classByTable;
    }
}

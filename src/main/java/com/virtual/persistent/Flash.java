package com.virtual.persistent;

import com.virtual.LockKey;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author zhuchuanji
 * @Description 刷库线程
 * @Create: 2026/5/31 6:44
 */
@Slf4j
public class Flash extends Thread {

    private final LinkedBlockingQueue<Map<LockKey, Operation>> tasks = new LinkedBlockingQueue<>();
    private volatile boolean ending = false;

    public Flash() {
        super("Flash");
    }

    @Override
    public void run() {
        Snapshot snapshot = Persistent.INSTANCE.getSnapshot();
        while (!snapshot.isEnd() || !tasks.isEmpty()) {
            Map<LockKey, Operation> poll = tasks.poll();
            if (poll == null) {
                continue;
            }
            log.info("flash 处理数据量 {}", poll.size());
            try {
                // todo 需要走批量落库
                for (Map.Entry<LockKey, Operation> entry : poll.entrySet()) {
                    Operation value = entry.getValue();
                    Comparable<?> id = entry.getKey().id();
                    TableHelper<?> tableHelper = Persistent.INSTANCE.getTableHelper(value.t().getTableName(), value.t().getTableClass());
                    switch (value.s()) {
                        case DELETE -> tableHelper.delete(id);
                        case INSERT -> tableHelper.insert(value.w());
                        default -> tableHelper.update(id, value.w());
                    }
                }
            } catch (Exception e) {
                log.error("Flash 落库异常！！！", e);
            }
        }
        ending = true;
    }


    public void addTask(Map<LockKey, Operation> snapshot) {
        tasks.offer(snapshot);
    }

    public boolean isEnd() {
        return ending;
    }
}

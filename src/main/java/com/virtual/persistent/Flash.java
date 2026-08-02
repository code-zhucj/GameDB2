package com.virtual.persistent;

import com.virtual.LockKey;

import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author zhuchuanji
 * @Description 刷库线程
 * @Create: 2026/5/31 6:44
 */
public class Flash extends Thread {

    private final LinkedBlockingQueue<Map<LockKey, Operation>> tasks = new LinkedBlockingQueue<>();
    private volatile boolean ending = false;

    @Override
    public void run() {
        Snapshot snapshot = Persistent.INSTANCE.getSnapshot();
        while (!snapshot.isEnd()) {
            Map<LockKey, Operation> poll = tasks.poll();
            if (poll == null) {
                return;
            }
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

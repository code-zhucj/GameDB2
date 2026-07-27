package com.virtual.persistent;

import com.virtual.LockKey;
import com.virtual.Record;
import com.virtual.codec.Writer;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * @author zhuchuanji
 * @Description 快照线程
 * @Create: 2026/5/31 6:44
 */
@Slf4j
public class Snapshot extends Thread implements AutoCloseable {

    private static final int PERIOD = 60 * 1000 * 1000;

    private final AtomicBoolean lock = new AtomicBoolean(true); // 快照锁
    private final Map<LockKey, Record<?>> changed = new ConcurrentHashMap<>();
    private final Map<LockKey, Record<?>> _changed = new ConcurrentHashMap<>();
    private Map<LockKey, Writer> snapshot = new HashMap<>(); // 只会被快照线程读写
    private long nextSnapshotTime = System.currentTimeMillis(); // 下一次快照时间
    private volatile boolean running = true;

    public Snapshot() {
        super("snapshot");
    }


    public void onChanged(LockKey lockKey, Record<?> record) {
        if (lock.get()) {
            changed.put(lockKey, record);
        } else {
            _changed.put(lockKey, record);
        }
    }

    public void snapshot() {
        Map<LockKey, Record<?>> changed = lock.get() ? this.changed : this._changed;

        // 并发快照,与业务线程并行,使用读锁,快速做快照,在这一步的过程中应该大量对象都完成快照了
        serialization(changed);
        lock.set(!lock.get());
        // stop the world,需要在事物提交前或者在logic提交前加锁,这一步保证将changed中的所有对象都打完快照,这一步相当于让服务器停止逻辑了,这一步得快
        serialization(changed); // 第二次序列化,在当前过程中,changed列表不会在变化,将最后的数据-> snapshot 列表中

        // 将快照列表丢该flash线程,上一步已经保证了snapshot 中为某一时刻服务器的安全快照，之后的落库交给flash线程慢慢处理就行了
        Persistent.INSTANCE.getFlash().addTask(snapshot); // 这里的快照就是当前时刻内存页内的数据快照
        snapshot = new HashMap<>(); // 清空快照列表
    }

    private void serialization(Map<LockKey, Record<?>> changed) {
        for (Map.Entry<LockKey, Record<?>> entry : changed.entrySet()) {
            // 尝试读锁,没拿到等下一轮
            if (entry.getKey().tryReadLock()) {
                // 这里可能有几种情况列举一下 1. 如果当前被移除的记录在别的线程又有新的线程投递,则从时许上来说,处于remove之前,正好本次处理
                // 处于remove 之后则可以理解为本次处理的为旧的,新的在下一伦处理
                // 如果是其他key,则插入在当前迭代元素之前,则下一轮处理,插在当前则本论处理,之后则本轮后面正常处理
                try {
                    Record<?> copy = changed.remove(entry.getKey());
                    if (copy.getState() == Record.State.DELETE) {
                        // 不用序列化了
                        snapshot.put(entry.getKey(), null);
                    } else {
                        // 通常来讲,序列化这一步不会出问题了,毕竟序列化逻辑都是代码生成的
//                        Writer writer = new Writer();
//                        copy.getEntity().encode(writer);
//                        snapshot.put(entry.getKey(), writer);
                    }
                    log.debug("打个快照 {}", entry.getValue());
                } finally {
                    entry.getKey().readUnlock();
                }
            }
        }
    }


    @Override
    public void run() {
        while (running || !changed.isEmpty()) {
            if (System.nanoTime() >= this.nextSnapshotTime) {
                this.nextSnapshotTime = this.nextSnapshotTime + PERIOD;
                snapshot();
                long waitTime = this.nextSnapshotTime - System.currentTimeMillis();
                if (waitTime > 0) {
                    LockSupport.parkNanos(waitTime);
                }
            }
        }
    }

    @Override
    public void close() throws Exception {
        running = false;
    }
}

package com.virtual.persistent;

import com.virtual.GameDB;
import com.virtual.LockKey;
import com.virtual.Record;
import com.virtual.RowLock;
import com.virtual.codec.MongoWriter;
import com.virtual.codec.Writer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;

/**
 * @author zhuchuanji
 * @Description 快照线程
 * @Create: 2026/5/31 6:44
 */
@Slf4j
public class Snapshot extends Thread implements AutoCloseable {

    private static final long PERIOD = GameDB.getCONFIG().getSnapshotPeriod() * 1000 * 1000L;

    private final AtomicBoolean lock = new AtomicBoolean(true); // 快照锁
    private final BlockingQueue<Map<LockKey, Record<?>>> changed = new LinkedBlockingQueue<>();
    private final BlockingQueue<Map<LockKey, Record<?>>> _changed = new LinkedBlockingQueue<>();

    private Map<LockKey, Operation> snapshot = new HashMap<>(); // 只会被快照线程读写
    private long nextSnapshotTime = System.nanoTime(); // 下一次快照时间
    private volatile boolean running = true;
    @Getter
    private volatile boolean end = false;

    public Snapshot() {
        super("Snapshot");
    }


    public void onChanged(Map<LockKey, Record<?>> transactionPack) {
        if (lock.get()) {
            changed.add(transactionPack);
        } else {
            _changed.add(transactionPack);
        }
    }

    public void snapshot() {
        BlockingQueue<Map<LockKey, Record<?>>> current = lock.get() ? this.changed : this._changed;
        if (current.isEmpty()) {
            return;
        }
        lock.set(!lock.get());
        Map<LockKey, Record<?>> mergeChanged = new HashMap<>();
        // 这里将对事物进行合并,依据队列顺序，后来的事物覆盖前一个事物的值
        int count = 0;
        while (!current.isEmpty()) {
            Map<LockKey, Record<?>> poll = current.poll();
            count++;
            for (Map.Entry<LockKey, Record<?>> entry : poll.entrySet()) {
                mergeChanged.merge(entry.getKey(), entry.getValue(), (o, v) -> v);
            }
        }
        // 当前线程自旋,保证所有的数据都处理完毕
        while (!mergeChanged.isEmpty()) {
            serialization(mergeChanged);
            LockSupport.parkNanos(100_000);
        }
        log.info("本次快照执行记录数 {}, 事物数 {}", snapshot.size(), count);
        // 将快照列表丢该flash线程,上一步已经保证了snapshot 中为某一时刻服务器的安全快照，之后的落库交给flash线程慢慢处理就行了
        Persistent.INSTANCE.getFlash().addTask(snapshot); // 这里的快照就是当前时刻内存页内的数据快照
        snapshot = HashMap.newHashMap(snapshot.size()); // 清空快照列表
    }

    private void serialization(Map<LockKey, Record<?>> changed) {
        Iterator<Map.Entry<LockKey, Record<?>>> iterator = changed.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<LockKey, Record<?>> entry = iterator.next();
            // 尝试读锁,没拿到等下一轮
            RowLock rowLock = entry.getValue().getRowLock();
            Lock readLock = rowLock.readLock();
            if (readLock.tryLock()) {
                // 这里可能有几种情况列举一下 1. 如果当前被移除的记录在别的线程又有新的线程投递,则从时许上来说,处于remove之前,正好本次处理
                // 处于remove 之后则可以理解为本次处理的为旧的,新的在下一伦处理
                // 如果是其他key,则插入在当前迭代元素之前,则下一轮处理,插在当前则本论处理,之后则本轮后面正常处理
                try {
                    Record<?> copy = entry.getValue();
                    iterator.remove();
                    if (copy.getPersistent() == Record.State.DELETE) {
                        // 不用序列化了
                        snapshot.put(entry.getKey(), new Operation(copy.getTable(), copy.getState(), null));
                    } else {
                        Writer writer = new MongoWriter();
                        copy.getEntity().encode(writer);
                        snapshot.put(entry.getKey(), new Operation(copy.getTable(), copy.getPersistent(), writer));
                    }
                } catch (Exception e) {
                    // 这里其实不太可能出现异常了,假设这里出现了未知的异常,那么此时快照中数据已经是不对的了,那么需要直接退出进程,丢弃最近的改动
                    log.error("快照出现异常", e);
                    System.exit(0);
                } finally {
                    readLock.unlock();
                }
            }
        }
    }


    @Override
    public void run() {
        while (running || !changed.isEmpty() || !_changed.isEmpty()) {
            if (!running || System.nanoTime() >= this.nextSnapshotTime) {
                this.nextSnapshotTime = System.nanoTime() + PERIOD;
                long startTime = System.currentTimeMillis();
                log.info("快照 {}", startTime);
                snapshot();
                log.info("快照 {}, 耗时 {} ms", System.currentTimeMillis(), System.currentTimeMillis() - startTime);
                long waitTime = this.nextSnapshotTime - System.nanoTime();
                if (waitTime > 0) {
                    LockSupport.parkNanos(waitTime);
                }
            }
        }
        end = true;
    }

    @Override
    public void close() {
        running = false;
        LockSupport.unpark(this);
    }
}

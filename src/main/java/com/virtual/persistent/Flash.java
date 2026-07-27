package com.virtual.persistent;

import com.virtual.LockKey;
import com.virtual.codec.Writer;

import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author zhuchuanji
 * @Description 刷库线程
 * @Create: 2026/5/31 6:44
 */
public class Flash extends Thread implements AutoCloseable {

    private LinkedBlockingQueue<Map<LockKey, Writer>> tasks = new LinkedBlockingQueue<>();

    @Override
    public void run() {
        while (true) {
            Map<LockKey, Writer> poll = tasks.poll();
            if (poll == null) {
                continue;
            }
            for (Writer value : poll.values()) {
                // todo 调用,序列化是比较大的一块了,这里先不写,先验证内存事物如何,然后验证快照性能吧
//                TableHelper<TableDefine<?>> tableHelper = Persistent.INSTANCE.getTableHelper(null, null);
                // 先整理都要改哪些表,然后批量插入数据到库中，Writer中应该就是Bson结构,说白了现在的过程就是entity->bson->mongoClient
            }
        }
    }


    public void addTask(Map<LockKey, Writer> snapshot) {
        tasks.offer(snapshot);
    }

    @Override
    public void close() throws Exception {

    }
}

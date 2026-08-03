package com.virtual.persistent;

import com.virtual.TableDefine;
import com.virtual.TransactionImpl;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * @author zhuchuanji
 * @Description 持久化接口, 负责将内存数据落库
 * @Create: 2026/5/28 17:22
 */
@Slf4j
@Getter
public enum Persistent {
    INSTANCE;

    private final PersistentClient persistentClient = new MongoClient();
    private final Snapshot snapshot = new Snapshot();
    private final Flash flash = new Flash();

    Persistent() {
        snapshot.start();
        flash.start();
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                log.info("关闭前需要等待snapshot和flash无任务");
                snapshot.close();
                TransactionImpl.TRANSACTION_POOL.close();
                while (!snapshot.isEnd() || !flash.isEnd()) {
                    LockSupport.parkNanos(1_000_000_000);
                }
                log.info("安全关闭");
            }
        });
    }

    public <T extends TableDefine> TableHelper<T> getTableHelper(String tableName, Class<T> tableClass) {
        return persistentClient.getHelper(tableName, tableClass);
    }


}

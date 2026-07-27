package com.virtual.persistent;

import com.virtual.TableDefine;
import com.virtual.Tables;
import lombok.Getter;

/**
 * @author zhuchuanji
 * @Description 持久化接口, 负责将内存数据落库
 * @Create: 2026/5/28 17:22
 */
@Getter
public enum Persistent implements AutoCloseable {
    INSTANCE;

    private final PersistentClient persistentClient = new MemoryClient();
    private final Snapshot snapshot = new Snapshot();
    private final Flash flash = new Flash();

    Persistent() {
//        snapshot.start();
//        flash.start();
    }

    @SuppressWarnings("unchecked")
    public <T extends TableDefine<?>> TableHelper<T> getTableHelper(String tableName, Class<T> tableClass) {
        return (TableHelper<T>) persistentClient.getHelper(tableName, Tables.getProxyClass(tableClass));
    }


    @Override
    public void close() throws Exception {

    }
}

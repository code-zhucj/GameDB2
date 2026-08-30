package com.virtual.persistent;

import com.virtual.TableDefine;

/**
 * @author zhuchuanji
 * @Description 内存客户端, 不提供持久化相关功能
 * @Create: 2026/6/21 18:27
 */
public class MemoryClient implements PersistentClient {
    @Override
    public <T extends TableDefine> TableHelper<T> getHelper(String tableName, Class<T> tableClass) {
        return new TableHelper<T>() {
            @Override
            public T select(Comparable<?> key) {
                return null;
            }

            @Override
            public void delete(Comparable<?> key) {

            }

            @Override
            public void insert(T t) {

            }

            @Override
            public void update(T t) {

            }

            @Override
            public Iterable<T> selectByLimit(int cacheSize) {
                return null;
            }

            @Override
            public void batchWrite(java.util.List<BatchOp> ops) {
            }

            @Override
            public Comparable<?> maxKey() {
                return null;
            }
        };
    }

    @Override
    public void startTransaction() {
    }

    @Override
    public void commitTransaction() {
    }

    @Override
    public void abortTransaction() {
    }
}

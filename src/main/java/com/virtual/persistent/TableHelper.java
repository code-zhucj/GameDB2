package com.virtual.persistent;

/**
 * @author zhuchuanji
 * @Description 表相关操作
 * @Create: 2026/5/28 17:31
 */
public interface TableHelper<T> {

    T select(Comparable<?> key);

    void delete(Comparable<?> key);

    void insert(T t);

    void update(T t);

    Iterable<T> selectByLimit(int cacheSize);
}

package com.virtual.persistent;

import com.virtual.codec.Writer;

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

    default void insert(Writer writer){}
    default void update(Comparable<?> key, Writer writer){}
}

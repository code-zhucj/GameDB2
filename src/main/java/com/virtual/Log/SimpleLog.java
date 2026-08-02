package com.virtual.Log;

import java.util.function.Consumer;

public class SimpleLog<V> implements Log<V> {
    private V v;
    private Consumer<V> consumer;

    public SimpleLog(V v, Consumer<V> consumer) {
        this.v = v;
        this.consumer = consumer;
    }

    @Override
    public V getValue() {
        return v;
    }

    @Override
    public void commit() {
        consumer.accept(v);
    }

    @Override
    public void rollback() {

    }
}

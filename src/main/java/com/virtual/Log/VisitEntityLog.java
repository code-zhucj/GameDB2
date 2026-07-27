package com.virtual.Log;

import com.virtual.entity.VisitEntity;

import java.util.function.Consumer;

/**
 * @author zhuchuanji
 * @Description todo
 * @Create: 2026/7/19 3:22
 */
public class VisitEntityLog<E, T extends VisitEntity<E>> implements Log<E> {

    private final T entity;
    private final Consumer<E> consumer;

    public VisitEntityLog(T entity, Consumer<E> consumer) {
        this.entity = entity;
        this.consumer = consumer;
    }

    @Override
    public void commit() {
        entity.commit();
        consumer.accept(entity.getSource());
    }

    @Override
    public void rollback() {

    }

    @Override
    public E getValue() {
        return entity.getSource();
    }
}

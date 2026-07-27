package com.virtual.Log;

import java.util.function.Consumer;

public class EntityLog<E> extends SimpleLog<E> {
    public EntityLog(E entity, Consumer<E> consumer) {
        super(entity, consumer);
    }
}

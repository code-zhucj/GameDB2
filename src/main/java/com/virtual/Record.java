package com.virtual;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class Record<T extends TableDefine> {

    private int version;
    private State state = State.DB;
    // 持久化的状态
    private transient State persistent = State.DB;
    private T entity; // 这里的entity就是内存中的数据，不论有几个Record副本都是同一个权威数据
    private Table<T> table;
    private Comparable<?> primaryKey;

    private RowLock rowLock;

    public Record(State state, Table<T> table, Comparable<?> primaryKey) {
        this(state, 0, table, null, primaryKey);
    }

    public Record(State state, Table<T> table, T entity) {
        this(state, 0, table, entity, entity == null ? null : entity.primaryKey());
    }

    public Record(State state, int version, Table<T> table, T entity, Comparable<?> primaryKey) {
        this.state = state;
        this.version = version;
        this.entity = entity;
        this.table = table;
        this.primaryKey = primaryKey;
    }

    public Record<T> copy() {
        return new Record<>(state, version, table, entity, primaryKey);
    }

    public RowLock getRowLock() {
        return this.rowLock;
    }

    void bindLock(RowLock rowLock) {
        this.rowLock = rowLock;
    }


    public enum State {
        NULL,
        DB,
        UPDATE,
        INSERT,
        DELETE
    }
}

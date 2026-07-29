package com.virtual;

/** Caller-owned facade; keep a strong reference while the lock is in use. */
public record LockKey(String tableName, Comparable<?> id) implements Comparable<LockKey> {

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public int compareTo(LockKey o) {
        int cmp = tableName.compareTo(o.tableName);
        return cmp != 0 ? cmp : ((Comparable) id).compareTo(o.id);
    }
}

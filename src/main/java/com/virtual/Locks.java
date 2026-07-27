package com.virtual;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Locks {

    //todo 里面的key什么时候能保证移除，保证不泄露
    private final static Map<LockKey, LockKey> lockKeys = new ConcurrentHashMap<>();

    public static LockKey getOrCreateLockKey(String tableName, Comparable<?> key) {
        LockKey lockKey = new LockKey(tableName, (Comparable<Object>) key);
        return lockKeys.computeIfAbsent(lockKey, v -> lockKey);
    }


}

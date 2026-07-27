package com.virtual;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Slf4j
@AllArgsConstructor
public class LockKey implements Comparable<LockKey> {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    @Getter
    @NonNull
    private String name;
    @Getter
    @NonNull
    private Comparable<Object> key;

    private static final Comparator<LockKey> COMPARATOR = Comparator.comparing(LockKey::getName).thenComparing(LockKey::getKey);


    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;

        LockKey lockKey = (LockKey) o;
        return name.equals(lockKey.name) && key.equals(lockKey.key);
    }

    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + key.hashCode();
        return result;
    }

    @Override
    public int compareTo(@NonNull LockKey o) {
        return COMPARATOR.compare(this, o);
    }

    public boolean tryReadLock() {
        return lock.readLock().tryLock();
    }

    public void readLock() {
        lock.readLock().lock();
//        log.debug("线程 {} 获取读锁 {}", Thread.currentThread().getName(), key);
    }

    public void readUnlock() {
        lock.readLock().unlock();
//        log.debug("线程 {} 释放读锁 {}", Thread.currentThread().getName(), key);
    }

    public void writeLock(){
        lock.writeLock().lock();
//        log.debug("线程 {} 获取写锁 {}", Thread.currentThread().getName(), key);
    }

    public void writUnlock(){
        lock.writeLock().unlock();
//        log.debug("线程 {} 释放写锁 {}", Thread.currentThread().getName(), key);
    }


}

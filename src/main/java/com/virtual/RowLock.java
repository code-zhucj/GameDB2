package com.virtual;

import java.lang.ref.Reference;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class RowLock implements ReadWriteLock {

    private final ReentrantReadWriteLock reentrantReadWriteLock = new ReentrantReadWriteLock();
    protected LockKey lockKey;

    RowLock(LockKey lockKey) {
        this.lockKey = lockKey;
    }


    @Override
    public Lock readLock() {
        return reentrantReadWriteLock.readLock();
    }

    @Override
    public Lock writeLock() {
        return reentrantReadWriteLock.writeLock();
    }

}

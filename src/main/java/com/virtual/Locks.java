package com.virtual;


import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;

public class Locks {

    private static final ConcurrentHashMap<LockKey, LockEntry> locks = new ConcurrentHashMap<>();

    private static final ReferenceQueue<RowLock> queue = new ReferenceQueue<>();

    int size() {
        removeExpiredEntries();
        return locks.size();
    }

    public static RowLock getLock(com.virtual.LockKey lockKey) {
        while (true) {
            removeExpiredEntries();
            LockEntry lockEntry = locks.get(lockKey);
            if (lockEntry != null && lockEntry.get() != null) {
                return lockEntry.get();
            }
            LockEntry entry = locks.computeIfAbsent(lockKey, k -> new LockEntry(k, queue));
            RowLock lock = entry.get();
            if (lock != null) {
                return lock;
            }
            locks.remove(lockKey, entry);
        }
    }

    // 移除过期entry
    private static void removeExpiredEntries() {
        LockEntry lockEntry;
        while ((lockEntry = (LockEntry) queue.poll()) != null) {
            locks.remove(lockEntry.lockKey, lockEntry);
        }
    }

    private static class LockEntry extends WeakReference<RowLock> {
        private final LockKey lockKey;

        public LockEntry(LockKey lockKey, ReferenceQueue<RowLock> queue) {
            super(new RowLock(lockKey), queue);
            this.lockKey = lockKey;
        }
    }
}

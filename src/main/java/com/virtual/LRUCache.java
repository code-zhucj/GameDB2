package com.virtual;

import java.util.concurrent.ConcurrentHashMap;

/**
 * @author zhuchuanji
 * @Description 需要依据设置的容量大小对缓存内容进行淘汰（CLOCK 二次机会近似 LRU）
 * @Create: 2026/8/17 1:43
 */
class LRUCache<E extends TableDefine> extends ConcurrentHashMap<Comparable<?>, Record<E>> {

    private final int capacity;
    /**
     * 保护环形链表（插入/摘除/淘汰）的锁；get 不参与，读路径无锁。
     */
    private final Object lock = new Object();
    /**
     * key -> 环形链表节点。仅保存访问顺序与引用位，value 仍存于本表（ConcurrentHashMap）中，
     * 避免重复存储 value。
     */
    private final ConcurrentHashMap<Comparable<?>, Node> nodes = new ConcurrentHashMap<>();
    /**
     * 环形双向链表哨兵：head.next 为链表头，head.prev 为链表尾。
     */
    private final Node head = new Node(null);
    /**
     * 钟摆指针，仅在 {@link #evict()} 中（持锁）推进。
     */
    private Node hand;

    LRUCache(int capacity) {
        this.capacity = capacity;
        head.prev = head;
        head.next = head;
        hand = head;
    }

    @Override
    public Record<E> get(Object key) {
        Record<E> value = super.get(key);
        if (value != null) {
            Node node = nodes.get(key);
            if (node != null) {
                node.ref = true; // 无锁 volatile 写，标记最近使用
            }
        }
        return value;
    }

    @Override
    public Record<E> put(Comparable<?> key, Record<E> value) {
        synchronized (lock) {
            Record<E> old = super.put(key, value);
            Node node = nodes.get(key);
            if (node == null) {
                node = new Node(key);
                nodes.put(key, node);
                linkTail(node);
            }
            node.ref = true;
            while (size() > capacity) {
                evict();
            }
            return old;
        }
    }

    @Override
    public Record<E> remove(Object key) {
        synchronized (lock) {
            Record<E> old = super.remove(key);
            if (old != null) {
                Node node = nodes.remove(key);
                if (node != null) {
                    unlink(node);
                }
            }
            return old;
        }
    }

    private void linkTail(Node node) {
        node.prev = head.prev;
        node.next = head;
        head.prev.next = node;
        head.prev = node;
    }

    private void unlink(Node node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    /**
     * 钟摆推进，淘汰一个引用位为 false 的节点；遇到引用位为 true 的节点则清 0 给第二次机会。
     */
    private void evict() {
        while (true) {
            hand = hand.next;
            if (hand == head) {
                hand = head.next;
            }
            if (hand.ref) {
                hand.ref = false;
            } else {
                Node victim = hand;
                hand = victim.prev; // 指针回退到被摘除节点的前驱，避免悬空
                unlink(victim);
                nodes.remove(victim.key);
                super.remove(victim.key);
                return;
            }
        }
    }

    /**
     * 环形链表节点，仅保存 key、引用位与前驱/后继指针。
     */
    private static final class Node {
        final Comparable<?> key;
        volatile boolean ref;
        Node prev;
        Node next;

        Node(Comparable<?> key) {
            this.key = key;
        }
    }
}

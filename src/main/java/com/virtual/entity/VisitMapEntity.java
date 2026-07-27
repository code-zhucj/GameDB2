package com.virtual.entity;

import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * @author zhuchuanji
 * @Description 事务中访问 Map，支持 HashMap/TreeMap 类型保留。
 * put 时对新值（集合类型）直接创建 VisitEntity 缓存，用 no-copy 引用（线程独享），减少后续 get 的拷贝开销。
 * @Create: 2026/7/19 2:18
 */
public class VisitMapEntity<K, V> extends VisitEntity<Map<K, V>> implements Map<K, V> {

    // ---- 构造 ----

    /** 拷贝构造：对 source 做浅拷贝，隔离原始数据 */
    public VisitMapEntity(Map<K, V> source) {
        super(copy(source));
    }

    /** no-copy 构造：直接引用 source，用于线程独享场景（如 put 新值） */
    VisitMapEntity(Map<K, V> source, boolean direct) {
        super(source);
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Map<K, V> copy(Map<K, V> source) {
        if (source instanceof TreeMap) {
            return new TreeMap<>(source);
        } else if (source instanceof HashMap) {
            return new HashMap<>(source);
        } else {
            return new HashMap<>(source);
        }
    }

    // ---- Map 委托方法 ----

    @Override
    public int size() { return source.size(); }

    @Override
    public boolean isEmpty() { return source.isEmpty(); }

    @Override
    public boolean containsKey(Object key) { return source.containsKey(key); }

    @Override
    public boolean containsValue(Object value) { return source.containsValue(value); }

    @Override
    public V get(Object key) {
        V v = source.get(key);
        return v == null ? null : visit(key, v);
    }

    @Override
    @SuppressWarnings("unchecked")
    public V put(K key, V value) {
        if (value instanceof VisitEntity<?> ve) {
            // 解包：避免 VisitEntity 嵌套，直接用其 source
            visit.put(key, ve);
            return source.put(key, (V) ve.getSource());
        }
        V prev = source.put(key, value);
        cacheIfCollection(key, value);
        return prev;
    }

    @Override
    public V remove(Object key) {
        visit.remove(key);
        return source.remove(key);
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        for (K key : m.keySet()) {
            visit.remove(key);
        }
        source.putAll(m);
    }

    @Override
    public void clear() {
        visit.clear();
        source.clear();
    }

    @Override
    public Set<K> keySet() {
        return source.keySet();
    }

    @Override
    public Collection<V> values() {
        return new AbstractCollection<>() {
            @Override
            public Iterator<V> iterator() {
                Iterator<Entry<K, V>> it = source.entrySet().iterator();
                return new Iterator<>() {
                    @Override public boolean hasNext() { return it.hasNext(); }
                    @Override public V next() {
                        Entry<K, V> entry = it.next();
                        V v = entry.getValue();
                        return v == null ? null : visit(entry.getKey(), v);
                    }
                };
            }
            @Override public int size() { return source.size(); }
        };
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public Iterator<Entry<K, V>> iterator() {
                Iterator<Entry<K, V>> it = source.entrySet().iterator();
                return new Iterator<>() {
                    @Override public boolean hasNext() { return it.hasNext(); }
                    @Override public Entry<K, V> next() {
                        Entry<K, V> entry = it.next();
                        V v = entry.getValue();
                        V wrapped = v == null ? null : VisitMapEntity.this.visit(entry.getKey(), v);
                        if (wrapped == v) return entry;
                        return new AbstractMap.SimpleEntry<>(entry.getKey(), wrapped);
                    }
                };
            }
            @Override public int size() { return source.size(); }
        };
    }

    // ---- 内部方法 ----

    @SuppressWarnings("unchecked")
    private void cacheIfCollection(K key, V value) {
        if (value instanceof Map<?, ?> m) {
            VisitMapEntity<?, ?> ve = new VisitMapEntity<>(m, true);
            ve.setRoot(getRoot());
            visit.put(key, ve);
        } else if (value instanceof List<?> l) {
            VisitListEntity<?> ve = new VisitListEntity<>(l, true);
            ve.setRoot(getRoot());
            visit.put(key, ve);
        } else if (value instanceof Set<?> s) {
            VisitSetEntity<?> ve = new VisitSetEntity<>(s, true);
            ve.setRoot(getRoot());
            visit.put(key, ve);
        }
    }

    // ---- 提交 ----

    @Override
    @SuppressWarnings("unchecked")
    public void commit() {
        visit.forEach((k, e) -> {
            e.commit();
            source.put((K) k, (V) e.getSource());
        });
    }
}

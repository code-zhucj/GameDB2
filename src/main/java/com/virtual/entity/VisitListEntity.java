package com.virtual.entity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

/**
 * @author zhuchuanji
 * @Description 事务中访问 List，add/set 时对新值（集合类型）直接创建 VisitEntity 缓存，
 * 用 no-copy 引用（线程独享），减少后续 get 的拷贝开销。
 * @Create: 2026/7/19 4:19
 */
public class VisitListEntity<V> extends VisitEntity<List<V>> implements List<V> {

    // ---- 构造 ----

    /**
     * 拷贝构造：对 source 做浅拷贝，隔离原始数据
     */
    public VisitListEntity(List<V> source) {
        super(new ArrayList<>(source));
    }

    /**
     * no-copy 构造：直接引用 source，用于线程独享场景（如 add/set 新值）
     */
    VisitListEntity(List<V> source, boolean direct) {
        super(source);
    }

    // ---- visit 索引管理 ----

    /**
     * 将所有 visit key >= fromIndex 的 key 偏移 delta
     */
    private void shiftVisitKeys(int fromIndex, int delta) {
        if (delta == 0) return;
        Map<Object, VisitEntity<?>> shifted = new HashMap<>();
        visit.forEach((k, e) -> {
            if (k instanceof Integer idx && idx >= fromIndex) {
                shifted.put(idx + delta, e);
            } else {
                shifted.put(k, e);
            }
        });
        visit.clear();
        visit.putAll(shifted);
    }

    @SuppressWarnings("unchecked")
    private void cacheIfCollection(int index, V value) {
        if (value instanceof Map<?, ?> m) {
            VisitMapEntity<?, ?> ve = new VisitMapEntity<>(m, true);
            ve.setRoot(getRoot());
            visit.put(index, ve);
        } else if (value instanceof List<?> l) {
            VisitListEntity<?> ve = new VisitListEntity<>(l, true);
            ve.setRoot(getRoot());
            visit.put(index, ve);
        } else if (value instanceof Set<?> s) {
            VisitSetEntity<?> ve = new VisitSetEntity<>(s, true);
            ve.setRoot(getRoot());
            visit.put(index, ve);
        } else if (value.getClass().isArray()) {
            // todo 补充数组的安全访问
        }
    }

    // ---- 委托方法 ----

    @Override
    public int size() {
        return source.size();
    }

    @Override
    public boolean isEmpty() {
        return source.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        return source.contains(o);
    }

    @Override
    public Iterator<V> iterator() {
        return new Iterator<>() {
            private final Iterator<V> it = source.iterator();
            private int index = 0;

            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public V next() {
                V v = it.next();
                int idx = index++;
                return v == null ? null : visit(idx, v);
            }
        };
    }

    @Override
    public Object[] toArray() {
        return source.toArray();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        return source.toArray(a);
    }

    // ---- add: 新值直接缓存 VisitEntity ----

    @Override
    @SuppressWarnings("unchecked")
    public boolean add(V v) {
        int idx = source.size();
        if (v instanceof VisitEntity<?> ve) {
            visit.put(idx, ve);
            return source.add((V) ve.getSource());
        }
        cacheIfCollection(idx, v);
        return source.add(v);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void add(int index, V element) {
        if (element instanceof VisitEntity<?> ve) {
            shiftVisitKeys(index, 1);
            visit.put(index, ve);
            source.add(index, (V) ve.getSource());
            return;
        }
        shiftVisitKeys(index, 1);
        source.add(index, element);
        cacheIfCollection(index, element);
    }

    @Override
    public boolean addAll(Collection<? extends V> c) {
        if (c.isEmpty()) return false;
        int startIdx = source.size();
        boolean result = source.addAll(c);
        // 批量新增不逐个缓存，交由 get 懒创建
        return result;
    }

    @Override
    public boolean addAll(int index, Collection<? extends V> c) {
        if (c.isEmpty()) return false;
        shiftVisitKeys(index, c.size());
        return source.addAll(index, c);
    }

    // ---- set: 替换缓存 ----

    @Override
    @SuppressWarnings("unchecked")
    public V set(int index, V element) {
        if (element instanceof VisitEntity<?> ve) {
            visit.put(index, ve);
            return source.set(index, (V) ve.getSource());
        }
        V prev = source.set(index, element);
        cacheIfCollection(index, element);
        return prev;
    }

    // ---- remove / clear ----

    @Override
    public boolean remove(Object o) {
        int idx = source.indexOf(o);
        if (idx >= 0) {
            visit.remove(idx);
            shiftVisitKeys(idx + 1, -1);
        }
        return source.remove(o);
    }

    @Override
    public V remove(int index) {
        visit.remove(index);
        shiftVisitKeys(index + 1, -1);
        return source.remove(index);
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        visit.clear();
        return source.removeAll(c);
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        visit.clear();
        return source.retainAll(c);
    }

    @Override
    public void clear() {
        visit.clear();
        source.clear();
    }

    // ---- 查询 ----

    @Override
    public V get(int index) {
        V v = source.get(index);
        return v == null ? null : visit(index, v);
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return source.containsAll(c);
    }

    @Override
    public int indexOf(Object o) {
        return source.indexOf(o);
    }

    @Override
    public int lastIndexOf(Object o) {
        return source.lastIndexOf(o);
    }

    // ---- 提交 ----

    @Override
    @SuppressWarnings("unchecked")
    public void commit() {
        visit.forEach((k, e) -> {
            e.commit();
            source.set((int) k, (V) e.getSource());
        });
    }

    // ---- listIterator / subList 不包装（复杂度高，使用频率低） ----

    @Override
    public ListIterator<V> listIterator() {
        return source.listIterator();
    }

    @Override
    public ListIterator<V> listIterator(int index) {
        return source.listIterator(index);
    }

    @Override
    public List<V> subList(int fromIndex, int toIndex) {
        return source.subList(fromIndex, toIndex);
    }
}

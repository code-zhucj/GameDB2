package com.virtual.entity;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * @author zhuchuanji
 * @Description 事务中访问 Set，支持 HashSet/TreeSet 类型保留。
 * add 时对新值（集合类型）直接创建 VisitEntity 缓存，用 no-copy 引用（线程独享），减少后续遍历的拷贝开销。
 * @Create: 2026/7/19
 */
public class VisitSetEntity<E> extends VisitEntity<Set<E>> implements Set<E> {

    // ---- 构造 ----

    /** 拷贝构造：对 source 做浅拷贝，隔离原始数据 */
    public VisitSetEntity(Set<E> source) {
        super(copy(source));
    }

    /** no-copy 构造：直接引用 source，用于线程独享场景（如 add 新值） */
    VisitSetEntity(Set<E> source, boolean direct) {
        super(source);
    }

    @SuppressWarnings("unchecked")
    private static <E> Set<E> copy(Set<E> source) {
        if (source instanceof TreeSet) {
            return new TreeSet<>(source);
        } else if (source instanceof HashSet) {
            return new HashSet<>(source);
        } else {
            return new HashSet<>(source);
        }
    }

    // ---- Set 委托方法 ----

    @Override public int size() { return source.size(); }
    @Override public boolean isEmpty() { return source.isEmpty(); }
    @Override public boolean contains(Object o) { return source.contains(o); }

    @Override
    public Iterator<E> iterator() {
        return new Iterator<>() {
            private final Iterator<E> it = source.iterator();
            @Override public boolean hasNext() { return it.hasNext(); }
            @Override public E next() {
                E e = it.next();
                return e == null ? null : visit(e, e);
            }
        };
    }

    @Override public Object[] toArray() { return source.toArray(); }
    @Override public <T> T[] toArray(T[] a) { return source.toArray(a); }

    // ---- add: 新值直接缓存 VisitEntity ----

    @Override
    @SuppressWarnings("unchecked")
    public boolean add(E e) {
        if (e instanceof VisitEntity<?> ve) {
            visit.put(ve.getSource(), ve);
            return source.add((E) ve.getSource());
        }
        boolean result = source.add(e);
        cacheIfCollection(e);
        return result;
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        boolean modified = false;
        for (E e : c) {
            modified |= add(e);
        }
        return modified;
    }

    // ---- remove / clear ----

    @Override
    public boolean remove(Object o) {
        visit.remove(o);
        return source.remove(o);
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        for (Object o : c) visit.remove(o);
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

    @Override public boolean containsAll(Collection<?> c) { return source.containsAll(c); }

    // ---- 内部方法 ----

    @SuppressWarnings("unchecked")
    private void cacheIfCollection(E e) {
        if (e instanceof Map<?, ?> m) {
            VisitMapEntity<?, ?> ve = new VisitMapEntity<>(m, true);
            ve.setRoot(getRoot());
            visit.put(e, ve);
        } else if (e instanceof List<?> l) {
            VisitListEntity<?> ve = new VisitListEntity<>(l, true);
            ve.setRoot(getRoot());
            visit.put(e, ve);
        } else if (e instanceof Set<?> s) {
            VisitSetEntity<?> ve = new VisitSetEntity<>(s, true);
            ve.setRoot(getRoot());
            visit.put(e, ve);
        }
    }

    // ---- 提交: remove(old) + add(new) ----

    @Override
    @SuppressWarnings("unchecked")
    public void commit() {
        visit.forEach((k, e) -> {
            e.commit();
            source.remove(k);
            source.add((E) e.getSource());
        });
    }
}

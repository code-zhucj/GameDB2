package com.virtual.entity;

import com.virtual.Transaction;
import lombok.Getter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author zhuchuanji
 * @Description 访问
 * @Create: 2026/7/19 2:41
 */
public abstract class VisitEntity<S> extends Entity implements Transaction {

    @Getter
    final S source;
    final Map<Object, VisitEntity<?>> visit = new HashMap<>();

    public VisitEntity(S source) {
        this.source = source;
    }

    @SuppressWarnings("unchecked")
    <V> V visit(Object key, V v) {
        if (v instanceof VisitEntity) {
            return v;
        }
        VisitEntity<?> visitEntity = visit.get(key);
        if (visitEntity != null) {
            return (V) visitEntity;
        } else if (v instanceof Map<?, ?> m) {
            VisitMapEntity<?, ?> visitMapEntity = new VisitMapEntity<>(m);
            visitMapEntity.setRoot(this.getRoot());
            visit.put(key, visitMapEntity);
            return (V) visitMapEntity;
        } else if (v instanceof List<?> l) {
            VisitListEntity<?> visitListEntity = new VisitListEntity<>(l);
            visitListEntity.setRoot(this.getRoot());
            visit.put(key, visitListEntity);
            return (V) visitListEntity;
        } else if (v instanceof Set<?> s) {
            VisitSetEntity<?> visitSetEntity = new VisitSetEntity<>(s);
            visitSetEntity.setRoot(this.getRoot());
            visit.put(key, visitSetEntity);
            return (V) visitSetEntity;
        }
        return v;
    }


    @Override
    public void rollback() {

    }
}

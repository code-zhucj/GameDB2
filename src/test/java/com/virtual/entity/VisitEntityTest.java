package com.virtual.entity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VisitEntity 系列测试：VisitMapEntity、VisitListEntity、VisitSetEntity
 * <p>
 * commit 的核心语义：将 visit 中缓存的子 VisitEntity 变更写回到 source（工作拷贝）。
 * 最终由 VisitEntityLog 的 consumer 负责将 source 写回 Entity 字段。
 */
class VisitEntityTest {

    // ==================== VisitMapEntity ====================

    @Test
    void testMapGetBasic() {
        Map<String, Integer> source = new HashMap<>();
        source.put("a", 1);
        source.put("b", 2);

        VisitMapEntity<String, Integer> ve = new VisitMapEntity<>(source);
        assertEquals(1, ve.get("a"));
        assertEquals(2, ve.get("b"));
    }

    @Test
    void testMapGetNestedMap() {
        Map<String, Object> source = new HashMap<>();
        Map<String, Integer> inner = new HashMap<>();
        inner.put("x", 100);
        source.put("inner", inner);

        VisitMapEntity<String, Object> ve = new VisitMapEntity<>(source);
        Object result = ve.get("inner");
        assertInstanceOf(VisitMapEntity.class, result);

        @SuppressWarnings("unchecked")
        VisitMapEntity<String, Integer> innerVe = (VisitMapEntity<String, Integer>) result;
        assertEquals(100, innerVe.get("x"));
    }

    @Test
    void testMapGetNestedList() {
        Map<String, Object> source = new HashMap<>();
        List<Integer> inner = new ArrayList<>();
        inner.add(10);
        inner.add(20);
        source.put("list", inner);

        VisitMapEntity<String, Object> ve = new VisitMapEntity<>(source);
        Object result = ve.get("list");
        assertInstanceOf(VisitListEntity.class, result);

        @SuppressWarnings("unchecked")
        VisitListEntity<Integer> listVe = (VisitListEntity<Integer>) result;
        assertEquals(10, listVe.get(0));
        assertEquals(20, listVe.get(1));
    }

    @Test
    void testMapGetNestedSet() {
        Map<String, Object> source = new HashMap<>();
        Set<Integer> inner = new HashSet<>();
        inner.add(42);
        source.put("set", inner);

        VisitMapEntity<String, Object> ve = new VisitMapEntity<>(source);
        Object result = ve.get("set");
        assertInstanceOf(VisitSetEntity.class, result);
    }

    @Test
    void testMapSameKeyReturnsCachedVisitEntity() {
        Map<String, List<Integer>> source = new HashMap<>();
        source.put("list", new ArrayList<>(List.of(1, 2, 3)));

        VisitMapEntity<String, List<Integer>> ve = new VisitMapEntity<>(source);
        Object first = ve.get("list");
        Object second = ve.get("list");
        assertSame(first, second, "同一个 key 多次 get 应返回缓存的 VisitEntity");
    }

    @Test
    void testMapPutInvalidatesCache() {
        Map<String, List<Integer>> source = new HashMap<>();
        source.put("list", new ArrayList<>(List.of(1, 2, 3)));

        VisitMapEntity<String, List<Integer>> ve = new VisitMapEntity<>(source);
        Object first = ve.get("list");
        ve.put("list", new ArrayList<>(List.of(4, 5)));
        Object afterPut = ve.get("list");
        assertNotSame(first, afterPut, "put 后应返回新的 VisitEntity");
    }

    @Test
    void testMapRemoveInvalidatesCache() {
        Map<String, List<Integer>> source = new HashMap<>();
        source.put("list", new ArrayList<>(List.of(1, 2, 3)));

        VisitMapEntity<String, List<Integer>> ve = new VisitMapEntity<>(source);
        ve.get("list");  // 缓存 visit
        assertEquals(1, ve.visit.size());
        ve.remove("list");
        // visit 缓存已清理
        assertNull(ve.get("list"), "remove 后再去 get，source 中已无此 key");
    }

    @Test
    void testMapClearInvalidatesCache() {
        Map<String, List<Integer>> source = new HashMap<>();
        source.put("a", new ArrayList<>());
        source.put("b", new ArrayList<>());

        VisitMapEntity<String, List<Integer>> ve = new VisitMapEntity<>(source);
        ve.get("a");
        ve.get("b");
        assertEquals(2, ve.visit.size());
        ve.clear();
        assertTrue(ve.visit.isEmpty());
        assertTrue(ve.source.isEmpty());
    }

    @Test
    void testMapSourceModifiedByPut() {
        // 普通类型的 put 直接修改 source，commit 不参与（由 VisitEntityLog.consumer 写回）
        Map<String, Integer> original = new HashMap<>();
        original.put("key", 0);

        VisitMapEntity<String, Integer> ve = new VisitMapEntity<>(original);
        ve.put("key", 42);

        // source（工作拷贝）已被修改
        assertEquals(42, ve.getSource().get("key"));
        // 但原始 map 不变（isolation）
        assertEquals(0, original.get("key"));
    }

    @Test
    void testMapCommitNestedMapWritesBackToSource() {
        // 嵌套集合的修改经过 commit 写回到 VisitEntity.source
        Map<String, Object> original = new HashMap<>();
        Map<String, Integer> inner = new HashMap<>();
        inner.put("score", 0);
        original.put("data", inner);

        VisitMapEntity<String, Object> outer = new VisitMapEntity<>(original);

        @SuppressWarnings("unchecked")
        VisitMapEntity<String, Integer> innerVe = (VisitMapEntity<String, Integer>) outer.get("data");
        innerVe.put("score", 999);

        // commit 将 visit 中的子 VisitEntity 写回 outer.source
        outer.commit();

        @SuppressWarnings("unchecked")
        Map<String, Integer> resultInSource = (Map<String, Integer>) outer.getSource().get("data");
        assertEquals(999, resultInSource.get("score"));
    }

    @Test
    void testMapCommitNestedListWritesBackToSource() {
        Map<String, Object> original = new HashMap<>();
        List<String> inner = new ArrayList<>(List.of("a", "b"));
        original.put("items", inner);

        VisitMapEntity<String, Object> outer = new VisitMapEntity<>(original);

        @SuppressWarnings("unchecked")
        VisitListEntity<String> listVe = (VisitListEntity<String>) outer.get("items");
        listVe.set(0, "modified");
        listVe.add("c");

        outer.commit();

        @SuppressWarnings("unchecked")
        List<String> resultInSource = (List<String>) outer.getSource().get("items");
        assertEquals("modified", resultInSource.get(0));
        assertEquals("b", resultInSource.get(1));
        assertEquals("c", resultInSource.get(2));
    }

    @Test
    void testMapCommitNestedThroughVisitEntityLog() {
        // 模拟完整流程: VisitEntityLog.commit() → entity.commit() + consumer.accept(source)
        Map<String, List<Integer>> original = new HashMap<>();
        original.put("list", new ArrayList<>(List.of(1, 2, 3)));

        VisitMapEntity<String, List<Integer>> ve = new VisitMapEntity<>(original);

        @SuppressWarnings("unchecked")
        VisitListEntity<Integer> listVe = (VisitListEntity<Integer>) ve.get("list");
        listVe.add(99);

        // VisitEntityLog 的 consumer：将 source 写回
        Consumer<Map<String, List<Integer>>> consumer = original::putAll;
        ve.commit();                       // 1. 递归提交嵌套 VisitEntity
        consumer.accept(ve.getSource());   // 2. source 写回 original

        assertEquals(4, original.get("list").size());
        assertTrue(original.get("list").contains(99));
    }

    @Test
    void testMapValuesIterationWraps() {
        Map<String, List<Integer>> source = new HashMap<>();
        source.put("a", new ArrayList<>(List.of(1)));

        VisitMapEntity<String, List<Integer>> ve = new VisitMapEntity<>(source);
        for (List<Integer> v : ve.values()) {
            assertInstanceOf(VisitListEntity.class, v);
        }
    }

    @Test
    void testMapEntrySetIterationWrapsValue() {
        Map<String, List<Integer>> source = new HashMap<>();
        source.put("a", new ArrayList<>(List.of(1)));

        VisitMapEntity<String, List<Integer>> ve = new VisitMapEntity<>(source);
        for (Map.Entry<String, List<Integer>> entry : ve.entrySet()) {
            assertInstanceOf(VisitListEntity.class, entry.getValue());
        }
    }

    // ==================== TreeMap 保留 ====================

    @Test
    void testTreeMapTypePreserved() {
        Map<String, Integer> source = new TreeMap<>();
        source.put("b", 2);
        source.put("a", 1);

        VisitMapEntity<String, Integer> ve = new VisitMapEntity<>(source);
        assertInstanceOf(TreeMap.class, ve.getSource());

        // 验证排序：TreeMap 按 key 自然顺序，遍历应为 a, b
        List<String> keys = new ArrayList<>(ve.keySet());
        assertEquals("a", keys.get(0));
        assertEquals("b", keys.get(1));
    }

    // ==================== VisitListEntity ====================

    @Test
    void testListGetBasic() {
        List<String> source = new ArrayList<>(List.of("x", "y", "z"));
        VisitListEntity<String> ve = new VisitListEntity<>(source);
        assertEquals("x", ve.get(0));
        assertEquals("y", ve.get(1));
        assertEquals("z", ve.get(2));
    }

    @Test
    void testListGetNestedMap() {
        List<Object> source = new ArrayList<>();
        Map<String, Integer> inner = new HashMap<>();
        inner.put("k", 1);
        source.add(inner);

        VisitListEntity<Object> ve = new VisitListEntity<>(source);
        Object result = ve.get(0);
        assertInstanceOf(VisitMapEntity.class, result);
    }

    @Test
    void testListGetNestedList() {
        List<Object> source = new ArrayList<>();
        source.add(new ArrayList<>(List.of(1, 2, 3)));

        VisitListEntity<Object> ve = new VisitListEntity<>(source);
        Object result = ve.get(0);
        assertInstanceOf(VisitListEntity.class, result);
    }

    @Test
    void testListGetNestedSet() {
        List<Object> source = new ArrayList<>();
        source.add(new HashSet<>(Set.of(1, 2)));

        VisitListEntity<Object> ve = new VisitListEntity<>(source);
        Object result = ve.get(0);
        assertInstanceOf(VisitSetEntity.class, result);
    }

    @Test
    void testListSetInvalidatesCache() {
        List<List<Integer>> source = new ArrayList<>();
        source.add(new ArrayList<>(List.of(1)));

        VisitListEntity<List<Integer>> ve = new VisitListEntity<>(source);
        Object first = ve.get(0);
        ve.set(0, new ArrayList<>(List.of(999)));
        Object afterSet = ve.get(0);
        assertNotSame(first, afterSet, "set 后应创建新的 VisitEntity");
    }

    @Test
    void testListRemoveShiftsKeys() {
        List<List<Integer>> source = new ArrayList<>();
        source.add(new ArrayList<>(List.of(1)));  // index 0
        source.add(new ArrayList<>(List.of(2)));  // index 1

        VisitListEntity<List<Integer>> ve = new VisitListEntity<>(source);
        ve.get(0);
        ve.get(1);
        assertEquals(2, ve.visit.size());

        ve.remove(0);
        // index 0 的 visit 被删除，index 1 的 visit key 偏移为 0
        assertEquals(1, ve.visit.size(), "旧 index 1 的 wrapper 偏移到 key 0");

        // 通过 get(0) 验证偏移后的 wrapper 正确
        List<Integer> item = ve.get(0);
        assertEquals(2, item.get(0), "wrapper 对应的是原来 index 1 的元素 [2]");
    }

    @Test
    void testListAddAtIndexShiftsKeys() {
        List<List<Integer>> source = new ArrayList<>();
        source.add(new ArrayList<>(List.of(1)));  // index 0

        VisitListEntity<List<Integer>> ve = new VisitListEntity<>(source);
        ve.get(0);  // visit[(Integer)0] = wrapper for [1]

        ve.add(0, new ArrayList<>(List.of(0)));  // 在 index 0 插入，原 index 0 → index 1

        // 验证 source 数据正确
        assertEquals(0, ve.source.get(0).get(0));
        assertEquals(1, ve.source.get(1).get(0));
    }

    @Test
    void testListIteratorWraps() {
        List<List<Integer>> source = new ArrayList<>();
        source.add(new ArrayList<>(List.of(1)));

        VisitListEntity<List<Integer>> ve = new VisitListEntity<>(source);
        for (List<Integer> item : ve) {
            assertInstanceOf(VisitListEntity.class, item);
        }
    }

    @Test
    void testListCommitWritesBackToSource() {
        List<String> original = new ArrayList<>(List.of("a", "b"));
        VisitListEntity<String> ve = new VisitListEntity<>(original);

        // 嵌套集合：get 返回 VisitListEntity，修改之
        List<List<Integer>> nestedSource = new ArrayList<>();
        nestedSource.add(new ArrayList<>(List.of(1)));
        VisitListEntity<List<Integer>> ve2 = new VisitListEntity<>(nestedSource);

        @SuppressWarnings("unchecked")
        VisitListEntity<Integer> innerList = (VisitListEntity<Integer>) ve2.get(0);
        innerList.add(99);
        ve2.commit();

        assertTrue(ve2.getSource().get(0).contains(99), "嵌套 List 修改后 commit 写回 source");
    }

    @Test
    void testListCommitNestedMapWritesBackToSource() {
        List<Object> original = new ArrayList<>();
        Map<String, String> inner = new HashMap<>();
        inner.put("status", "old");
        original.add(inner);

        VisitListEntity<Object> ve = new VisitListEntity<>(original);
        @SuppressWarnings("unchecked")
        VisitMapEntity<String, String> innerVe = (VisitMapEntity<String, String>) ve.get(0);
        innerVe.put("status", "new");

        ve.commit();

        @SuppressWarnings("unchecked")
        Map<String, String> resultInSource = (Map<String, String>) ve.getSource().get(0);
        assertEquals("new", resultInSource.get("status"));
    }

    // ==================== VisitSetEntity ====================

    @Test
    void testSetBasicOperations() {
        Set<String> source = new HashSet<>(Set.of("a", "b"));
        VisitSetEntity<String> ve = new VisitSetEntity<>(source);

        assertEquals(2, ve.size());
        assertTrue(ve.contains("a"));
        assertFalse(ve.contains("c"));
    }

    @Test
    void testSetIteratorWrapsNestedList() {
        Set<List<Integer>> source = new HashSet<>();
        source.add(new ArrayList<>(List.of(1)));

        VisitSetEntity<List<Integer>> ve = new VisitSetEntity<>(source);
        for (List<Integer> item : ve) {
            assertInstanceOf(VisitListEntity.class, item);
        }
    }

    @Test
    void testSetIteratorWrapsNestedMap() {
        Set<Map<String, Integer>> source = new HashSet<>();
        Map<String, Integer> inner = new HashMap<>();
        inner.put("x", 1);
        source.add(inner);

        VisitSetEntity<Map<String, Integer>> ve = new VisitSetEntity<>(source);
        for (Map<String, Integer> item : ve) {
            assertInstanceOf(VisitMapEntity.class, item);
        }
    }

    @Test
    void testSetIteratorWrapsNestedSet() {
        Set<Set<Integer>> source = new HashSet<>();
        source.add(new HashSet<>(Set.of(1)));

        VisitSetEntity<Set<Integer>> ve = new VisitSetEntity<>(source);
        for (Set<Integer> item : ve) {
            assertInstanceOf(VisitSetEntity.class, item);
        }
    }

    @Test
    void testSetAddInvalidatesCache() {
        Set<List<Integer>> source = new HashSet<>();
        source.add(new ArrayList<>(List.of(1)));

        VisitSetEntity<List<Integer>> ve = new VisitSetEntity<>(source);
        for (List<Integer> ignored : ve) {
            // iterate to populate cache
        }
        assertEquals(1, ve.visit.size());

        ve.add(new ArrayList<>(List.of(2)));
        assertEquals(2, ve.size());
    }

    @Test
    void testSetRemoveInvalidatesCache() {
        Set<List<Integer>> source = new HashSet<>();
        List<Integer> elem = new ArrayList<>(List.of(1));
        source.add(elem);

        VisitSetEntity<List<Integer>> ve = new VisitSetEntity<>(source);
        for (List<Integer> ignored : ve) {
            // populate cache
        }
        assertEquals(1, ve.visit.size());
        ve.remove(elem);
        assertTrue(ve.visit.isEmpty());
    }

    @Test
    void testSetClearInvalidatesCache() {
        Set<List<Integer>> source = new HashSet<>();
        source.add(new ArrayList<>(List.of(1)));

        VisitSetEntity<List<Integer>> ve = new VisitSetEntity<>(source);
        for (List<Integer> ignored : ve) {
            // populate cache
        }
        assertEquals(1, ve.visit.size());
        ve.clear();
        assertTrue(ve.visit.isEmpty());
        assertTrue(ve.source.isEmpty());
    }

    @Test
    void testSetCommitWritesBackToSource() {
        Set<List<Integer>> original = new HashSet<>();
        List<Integer> inner = new ArrayList<>(List.of(1));
        original.add(inner);

        VisitSetEntity<List<Integer>> ve = new VisitSetEntity<>(original);
        for (List<Integer> item : ve) {
            item.add(999);
        }
        ve.commit();

        // commit 写回 ve.source，通过 remove+add 替换
        List<Integer> result = ve.getSource().iterator().next();
        assertTrue(result.contains(999));
    }

    @Test
    void testSetCommitNestedMapWritesBackToSource() {
        Set<Map<String, String>> original = new HashSet<>();
        Map<String, String> inner = new HashMap<>();
        inner.put("k", "old");
        original.add(inner);

        VisitSetEntity<Map<String, String>> ve = new VisitSetEntity<>(original);
        for (Map<String, String> item : ve) {
            item.put("k", "new");
        }
        ve.commit();

        Map<String, String> result = ve.getSource().iterator().next();
        assertEquals("new", result.get("k"));
    }

    @Test
    void testSetCommitThroughVisitEntityLog() {
        Set<List<Integer>> original = new HashSet<>();
        original.add(new ArrayList<>(List.of(1)));

        VisitSetEntity<List<Integer>> ve = new VisitSetEntity<>(original);
        for (List<Integer> item : ve) {
            item.add(42);
        }

        // 模拟 VisitEntityLog 完整流程
        Consumer<Set<List<Integer>>> consumer = s -> {
            original.clear();
            original.addAll(s);
        };
        ve.commit();
        consumer.accept(ve.getSource());

        assertTrue(original.iterator().next().contains(42));
    }

    // ==================== HashSet / TreeSet 类型保留 ====================

    @Test
    void testHashSetTypePreserved() {
        Set<Integer> source = new HashSet<>(Set.of(1, 2, 3));
        VisitSetEntity<Integer> ve = new VisitSetEntity<>(source);
        assertInstanceOf(HashSet.class, ve.getSource());
    }

    @Test
    void testTreeSetTypePreserved() {
        Set<Integer> source = new TreeSet<>(Set.of(3, 1, 2));
        VisitSetEntity<Integer> ve = new VisitSetEntity<>(source);
        assertInstanceOf(TreeSet.class, ve.getSource());

        // 验证排序
        List<Integer> ordered = new ArrayList<>(ve.getSource());
        assertEquals(1, ordered.get(0));
        assertEquals(2, ordered.get(1));
        assertEquals(3, ordered.get(2));
    }

    // ==================== 多层嵌套 ====================

    @Test
    void testTripleNestedMapListMap() {
        // Map<String, List<Map<String, Integer>>>
        Map<String, Object> original = new HashMap<>();
        List<Object> list = new ArrayList<>();
        Map<String, Integer> innerMap = new HashMap<>();
        innerMap.put("deep", 100);
        list.add(innerMap);
        original.put("outer", list);

        VisitMapEntity<String, Object> outer = new VisitMapEntity<>(original);

        @SuppressWarnings("unchecked")
        VisitListEntity<Object> listVe = (VisitListEntity<Object>) outer.get("outer");

        @SuppressWarnings("unchecked")
        VisitMapEntity<String, Integer> innerVe = (VisitMapEntity<String, Integer>) listVe.get(0);

        innerVe.put("deep", 200);

        // commit 逐层写回 outer.source
        outer.commit();

        @SuppressWarnings("unchecked")
        List<Object> resultInSource = (List<Object>) outer.getSource().get("outer");
        @SuppressWarnings("unchecked")
        Map<String, Integer> resultMap = (Map<String, Integer>) resultInSource.get(0);
        assertEquals(200, resultMap.get("deep"));
    }

    @Test
    void testNestedSetInMapCommitChain() {
        // Map<String, Set<List<Integer>>>
        Map<String, Object> original = new HashMap<>();
        Set<List<Integer>> innerSet = new HashSet<>();
        List<Integer> innerList = new ArrayList<>(List.of(1));
        innerSet.add(innerList);
        original.put("set", innerSet);

        VisitMapEntity<String, Object> ve = new VisitMapEntity<>(original);

        @SuppressWarnings("unchecked")
        VisitSetEntity<List<Integer>> setVe = (VisitSetEntity<List<Integer>>) ve.get("set");

        for (List<Integer> item : setVe) {
            item.add(42);
        }

        ve.commit();

        @SuppressWarnings("unchecked")
        Set<List<Integer>> resultInSource = (Set<List<Integer>>) ve.getSource().get("set");
        assertTrue(resultInSource.iterator().next().contains(42));
    }

    // ==================== 边界情况 ====================

    @Test
    void testMapGetNullValue() {
        Map<String, String> source = new HashMap<>();
        source.put("key", null);

        VisitMapEntity<String, String> ve = new VisitMapEntity<>(source);
        assertNull(ve.get("key"));
    }

    @Test
    void testMapGetMissingKey() {
        Map<String, String> source = new HashMap<>();
        VisitMapEntity<String, String> ve = new VisitMapEntity<>(source);
        assertNull(ve.get("nonexistent"));
    }

    @Test
    void testListGetNullElement() {
        List<String> source = new ArrayList<>();
        source.add(null);

        VisitListEntity<String> ve = new VisitListEntity<>(source);
        assertNull(ve.get(0));
    }

    @Test
    void testMapSourceIsolation() {
        Map<String, List<Integer>> original = new HashMap<>();
        original.put("a", new ArrayList<>(List.of(1)));

        VisitMapEntity<String, List<Integer>> ve = new VisitMapEntity<>(original);
        // 修改 VisitEntity 的 source 不影响 original
        ve.source.put("b", new ArrayList<>());
        assertFalse(original.containsKey("b"), "VisitEntity 的 source 是独立拷贝");
    }

    // ==================== put/add 新行为：eager 缓存 + VisitEntity 解包 ====================

    @Test
    void testMapPutCollectionTypeEagerlyCached() {
        // put 集合类型 → 直接在 visit 中创建 VisitEntity（no-copy），下次 get 命中缓存
        Map<String, List<Integer>> source = new HashMap<>();
        VisitMapEntity<String, List<Integer>> ve = new VisitMapEntity<>(source);

        ArrayList<Integer> newList = new ArrayList<>(List.of(42));
        ve.put("key", newList);

        // put 后 visit 缓存中已有 VisitEntity
        assertEquals(1, ve.visit.size());
        assertTrue(ve.visit.containsKey("key"));

        // get 直接命中 visit 缓存，不走 visit() 的创建分支
        Object cached = ve.get("key");
        assertInstanceOf(VisitListEntity.class, cached);
        assertSame(cached, ve.visit.get("key"), "get 应直接返回 visit 中的缓存");
    }

    @Test
    void testMapPutVisitEntityUnwrapped() {
        // put VisitEntity → 解包存 source，VisitEntity 放入 visit（不嵌套）
        Map<String, List<Integer>> original = new HashMap<>();
        VisitMapEntity<String, List<Integer>> outer = new VisitMapEntity<>(original);

        // 创建子 VisitEntity
        VisitListEntity<Integer> child = new VisitListEntity<>(new ArrayList<>(List.of(1, 2)));
        outer.put("items", child);

        // verify：source 中存的是原始 List（VisitEntity 的 source），不是 VisitEntity 本身
        assertFalse(outer.source.get("items") instanceof VisitEntity,
                "source 中不应嵌套 VisitEntity");

        // visit 中存的是 child 引用
        assertSame(child, outer.visit.get("items"));

        // get 返回 child
        assertSame(child, outer.get("items"));
    }

    @Test
    void testMapPutPlainValueNotCached() {
        // put 普通类型（Integer）→ source 直接存，visit 不缓存
        Map<String, Integer> source = new HashMap<>();
        VisitMapEntity<String, Integer> ve = new VisitMapEntity<>(source);
        ve.put("key", 42);

        assertFalse(ve.visit.containsKey("key"), "普通类型不应在 visit 中");
        assertEquals(42, ve.get("key"));
    }

    @Test
    void testListAddCollectionTypeEagerlyCached() {
        // add 集合类型 → visit 缓存（no-copy），下次 get 命中
        List<List<Integer>> source = new ArrayList<>();
        VisitListEntity<List<Integer>> ve = new VisitListEntity<>(source);

        ve.add(new ArrayList<>(List.of(1)));

        assertEquals(1, ve.visit.size());
        Object cached = ve.get(0);
        assertInstanceOf(VisitListEntity.class, cached);
        assertSame(cached, ve.visit.get(0));
    }

    @Test
    void testListAddVisitEntityUnwrapped() {
        // add VisitEntity → 解包，VisitEntity 放入 visit 缓存
        List<List<Integer>> source = new ArrayList<>();
        VisitListEntity<List<Integer>> ve = new VisitListEntity<>(source);

        VisitListEntity<Integer> child = new VisitListEntity<>(new ArrayList<>(List.of(99)));
        ve.add(child);

        // source 中存的是原始值
        assertFalse(ve.source.get(0) instanceof VisitEntity);
        // visit 缓存中是 child
        assertSame(child, ve.visit.get(0));
        // get 返回 child
        assertSame(child, ve.get(0));
    }

    @Test
    void testListSetCollectionTypeEagerlyCached() {
        // set 集合类型 → 替换 source + 替换 visit 缓存
        List<List<Integer>> source = new ArrayList<>();
        source.add(new ArrayList<>(List.of(1)));
        VisitListEntity<List<Integer>> ve = new VisitListEntity<>(source);

        VisitListEntity<?> old = (VisitListEntity<?>) ve.get(0);
        ve.set(0, new ArrayList<>(List.of(2)));

        // visit 中是新 VisitEntity
        assertNotSame(old, ve.visit.get(0));
        assertEquals(2, ve.source.get(0).get(0));
    }

    @Test
    void testSetAddCollectionTypeEagerlyCached() {
        // add 集合类型 → visit 缓存，用元素自身作为 key
        Set<List<Integer>> source = new HashSet<>();
        VisitSetEntity<List<Integer>> ve = new VisitSetEntity<>(source);

        ArrayList<Integer> elem = new ArrayList<>(List.of(1));
        ve.add(elem);

        assertEquals(1, ve.visit.size());
        // 遍历出来的就是缓存的 VisitListEntity
        for (List<Integer> item : ve) {
            assertInstanceOf(VisitListEntity.class, item);
        }
    }

    @Test
    void testSetAddVisitEntityUnwrapped() {
        Set<List<Integer>> source = new HashSet<>();
        VisitSetEntity<List<Integer>> ve = new VisitSetEntity<>(source);

        VisitListEntity<Integer> child = new VisitListEntity<>(new ArrayList<>(List.of(5)));
        ve.add(child);

        // source 中无 VisitEntity 嵌套
        for (List<Integer> item : ve.source) {
            assertFalse(item instanceof VisitEntity);
        }
        // visit 中以 child 的 source 为 key
        assertTrue(ve.visit.containsKey(child.getSource()));
    }

    @Test
    void testNestedVisitEntityNotDoubleWrapped() {
        // 多层嵌套：get 返回 VisitEntity，再 put 到另一个 Map，不应双重包装
        Map<String, List<Integer>> source1 = new HashMap<>();
        source1.put("a", new ArrayList<>(List.of(1)));
        VisitMapEntity<String, List<Integer>> ve1 = new VisitMapEntity<>(source1);

        Map<String, Object> source2 = new HashMap<>();
        VisitMapEntity<String, Object> ve2 = new VisitMapEntity<>(source2);

        // get 返回 VisitListEntity，put 到 ve2
        Object nested = ve1.get("a");
        ve2.put("ref", nested);

        // ve2.get 应返回同一个 VisitListEntity（不双重包装）
        assertSame(nested, ve2.get("ref"));
        // ve2.source 中不应有 VisitEntity
        assertFalse(ve2.source.get("ref") instanceof VisitEntity);
    }
}

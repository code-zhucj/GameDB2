package com.virtual.api;

import java.util.function.LongSupplier;

/**
 * @author zhuchuanji
 * @Description 自动生成主键
 * @Create: 2026/8/16 23:54
 */
public interface AutoPrimaryKey<K extends Comparable<?>> {

    K nextKey();

    /**
     * 判断当前主键是否需要自动生成，默认空值或数值 0 时生成；可覆写自定义策略
     */
    default boolean shouldGenerate(Comparable<?> key) {
        return key == null || (key instanceof Number n && n.longValue() == 0L);
    }

    /**
     * 内置 long 主键自增方案，基于计数器自增
     */
    class TableAutoKey implements AutoPrimaryKey<Long> {

        private final LongSupplier supplier;

        private TableAutoKey(LongSupplier supplier) {
            this.supplier = supplier;
        }

        public static TableAutoKey of(LongSupplier supplier) {
            return new TableAutoKey(supplier);
        }

        @Override
        public Long nextKey() {
            return supplier.getAsLong();
        }
    }
}

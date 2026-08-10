package com.virtual.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * TableConfig
 */
@Inherited
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TableConfig {

    /**
     * 用于定义表名,未设置直接使用类名作为表名
     */
    String value() default "";

    /**
     * 表默认缓存的数量量大小
     */
    int cacheSize() default 10000;

    /**
     * 是否立即落库，true 时事务提交后立即触发快照，不再等待 snapshotPeriod
     */
    boolean immediate() default false;

    /**
     * 表类型
     */
    Type type() default Type.DB;

    enum Type {
        /**
         * 内存表,无需持久化
         */
        MEMORY,
        /**
         * DB表,需要持久化
         */
        DB
    }
}

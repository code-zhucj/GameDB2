package com.virtual.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Inherited
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface T {

    /**
     * 用于定义表名,未设置直接使用类名作为表名
     */
    String value() default "";

    /**
     * 表默认缓存的数量量大小
     */
    int cacheSize() default 10000;

    /**
     * todo 需支持立即落库,思路是当前表触发修改时将当前事物标记为立即落库,直接修改下一次快照时间为当前即可实现
     *
     */
    boolean immediate() default false;

    Type type() default Type.DB;

    enum Type {
        MEMORY, DB
    }
}

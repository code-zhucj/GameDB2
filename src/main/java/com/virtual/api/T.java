package com.virtual.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

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
}

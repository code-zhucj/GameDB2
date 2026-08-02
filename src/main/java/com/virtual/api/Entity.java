package com.virtual.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记在 Entity 基类上，编译时扫描所有子类并自动生成 _XXX 代理类
 */
@Inherited
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface Entity {
}

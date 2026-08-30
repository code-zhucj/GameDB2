package com.virtual.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author zhuchuanji
 * @Description 主键
 * @Create: 2026/8/2 18:13
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Id {

    Class<? extends AutoPrimaryKey<?>> autoKeyGen() default AutoPrimaryKey.TableAutoKey.class;
}

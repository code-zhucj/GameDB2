package com.virtual.api;

import com.virtual.TableDefine;

/**
 * @author zhuchuanji
 * @Description 实现这个接口, 让框架能直接读到有哪些表
 * @Create: 2026/5/31 5:48
 */
public interface TableProvider {

    <E extends TableDefine> E create(Class<? extends TableDefine> tableClass);

    Class<? extends TableDefine> getProxyClass(Class<? extends TableDefine> tableClass);

    Iterable<Class<? extends TableDefine>> allTableClass();
}

package com.virtual;

import com.virtual.entity.Entity;

/**
 * @author zhuchuanji
 * @Description 表定义
 * @Create: 2026/5/25 22:28
 */
public abstract class TableDefine<K extends Comparable<?>> extends Entity {

    private byte state;

    public abstract K primaryKey();

    public boolean isInit() {
        return state == 0;
    }
    public void setInitComplete() {
        state = 1;
    }
}

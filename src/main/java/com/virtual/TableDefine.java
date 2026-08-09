package com.virtual;

import com.virtual.api.T;
import com.virtual.entity.Entity;

/**
 * @author zhuchuanji
 * @Description 表定义
 * @Create: 2026/5/25 22:28
 */
@T
public abstract class TableDefine extends Entity {

    public Comparable<?> primaryKey() {
        throw new UnsupportedOperationException("未定义主键");
    }

}

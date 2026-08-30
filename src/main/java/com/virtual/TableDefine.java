package com.virtual;

import com.virtual.api.TableConfig;
import com.virtual.entity.Entity;

/**
 * @author zhuchuanji
 * @Description 表定义
 * @Create: 2026/5/25 22:28
 */
@TableConfig
public abstract class TableDefine extends Entity {
    // 表定义中或许可以加个更新时间并以此加个索引,在起服时自动按最近更新加载,或许能提高缓存命中率
    public Comparable<?> primaryKey() {
        throw new UnsupportedOperationException("未定义主键");
    }

    public void setPrimaryKey(Comparable<?> key) {
        throw new UnsupportedOperationException("未定义主键");
    }

}

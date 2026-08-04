package com.virtual.persistent;

import com.virtual.Record;
import com.virtual.Table;
import com.virtual.codec.Writer;

/**
 * @author zhuchuanji
 * @Description 数据库操作元组
 * @Create: 2026/8/2 22:12
 */
public record Operation(Table<?> t, Record.State s, Writer w) {
}

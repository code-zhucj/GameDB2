package com.virtual.persistent;

import com.virtual.codec.Writer;

/**
 * @author zhuchuanji
 * @Description 批量操作封装，包含操作类型、主键和已编码的数据
 * @Create: 2026/8/5
 */
public record BatchOp(BatchOpType type, Comparable<?> key, Writer writer) {
}

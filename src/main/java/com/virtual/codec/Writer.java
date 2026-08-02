package com.virtual.codec;

/**
 * 实体编码写入器。将 Entity 字段写入底层存储格式（如 BSON）。
 * <p>
 * 命名方法（带 name 参数）用于文档字段写入；
 * 未命名方法用于数组元素写入。
 */
public interface Writer {

    // ---- 文档结构 ----

    /** 开始一个顶层或嵌套文档（无名） */
    void writeStartDocument();

    /** 结束当前文档 */
    void writeEndDocument();

    /** 开始一个具名子文档 */
    void writeStartDocument(String name);

    /** 开始一个具名数组 */
    void writeStartArray(String name);

    /** 结束当前数组 */
    void writeEndArray();

    // ---- 具名字段写入（用于文档字段） ----

    /** 仅写入字段名（不写值），之后需紧跟一个值写入调用 */
    void writeName(String name);

    void writeInt32(String name, int value);

    void writeInt64(String name, long value);

    void writeString(String name, String value);

    void writeNull(String name);

    // ---- 未命名写入（用于数组元素） ----

    void writeInt32(int value);

    void writeInt64(long value);

    void writeString(String value);
}

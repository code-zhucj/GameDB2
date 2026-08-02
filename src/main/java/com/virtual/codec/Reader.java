package com.virtual.codec;

/**
 * 实体解码读取器。从底层存储格式（如 BSON）读取字段并还原为 Entity。
 *
 * <h3>使用模式</h3>
 * <pre>{@code
 * reader.readStartDocument();
 * while (reader.readBsonType() != Reader.END_OF_DOCUMENT) {
 *     String name = reader.readName();
 *     switch (name) {
 *         case "id": entity.setId(reader.readInt32()); break;
 *         // ...
 *         default: reader.skipValue(); break;
 *     }
 * }
 * reader.readEndDocument();
 * }</pre>
 */
public interface Reader {

    // ---- BSON 类型常量 ----

    int END_OF_DOCUMENT = 0x00;
    int DOUBLE = 0x01;
    int STRING = 0x02;
    int DOCUMENT = 0x03;
    int ARRAY = 0x04;
    int INT32 = 0x10;
    int INT64 = 0x12;

    // ---- 文档结构 ----

    void readStartDocument();
    void readEndDocument();
    void readStartArray();
    void readEndArray();

    // ---- 迭代 ----

    /** 读取下一个元素的 BSON 类型，文档结束时返回 {@link #END_OF_DOCUMENT} */
    int readBsonType();

    /** 读取当前元素的字段名 */
    String readName();

    // ---- 值读取 ----

    int readInt32();
    long readInt64();
    String readString();

    /** 跳过当前元素的值 */
    void skipValue();
}

package com.virtual.codec;

import org.bson.BsonBinaryWriter;
import org.bson.RawBsonDocument;
import org.bson.io.BasicOutputBuffer;

/**
 * MongoDB BSON 写入器。将 Entity 字段写入 BSON 格式。
 * <p>
 * 内部使用 {@link BsonBinaryWriter} + {@link BasicOutputBuffer}，
 * 写入完成后通过 {@link #toRawBsonDocument()} 获取零拷贝的 {@link RawBsonDocument}。
 */
public class MongoWriter implements Writer {

    private final BasicOutputBuffer buffer;
    private final BsonBinaryWriter bsonWriter;

    public MongoWriter() {
        this.buffer = new BasicOutputBuffer();
        this.bsonWriter = new BsonBinaryWriter(buffer);
    }

    // ---- 文档结构 ----

    @Override
    public void writeStartDocument() {
        bsonWriter.writeStartDocument();
    }

    @Override
    public void writeEndDocument() {
        bsonWriter.writeEndDocument();
    }

    @Override
    public void writeStartDocument(String name) {
        bsonWriter.writeStartDocument(name);
    }

    @Override
    public void writeStartArray(String name) {
        bsonWriter.writeStartArray(name);
    }

    @Override
    public void writeEndArray() {
        bsonWriter.writeEndArray();
    }

    // ---- 具名字段写入 ----

    @Override
    public void writeName(String name) {
        bsonWriter.writeName(name);
    }

    @Override
    public void writeInt32(String name, int value) {
        bsonWriter.writeInt32(name, value);
    }

    @Override
    public void writeInt64(String name, long value) {
        bsonWriter.writeInt64(name, value);
    }

    @Override
    public void writeString(String name, String value) {
        bsonWriter.writeString(name, value);
    }

    @Override
    public void writeNull(String name) {
        bsonWriter.writeNull(name);
    }

    // ---- 未命名写入（数组元素） ----

    @Override
    public void writeInt32(int value) {
        bsonWriter.writeInt32(value);
    }

    @Override
    public void writeInt64(long value) {
        bsonWriter.writeInt64(value);
    }

    @Override
    public void writeString(String value) {
        bsonWriter.writeString(value);
    }

    // ---- 获取结果 ----

    /** 获取已写入的 BSON 字节数组 */
    public byte[] toByteArray() {
        bsonWriter.flush();
        return buffer.toByteArray();
    }

    /** 将已写入的数据转为 RawBsonDocument，传给 MongoDB 驱动时可跳过二次编码 */
    public RawBsonDocument toRawBsonDocument() {
        return new RawBsonDocument(toByteArray());
    }
}

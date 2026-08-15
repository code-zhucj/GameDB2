package com.virtual.codec;

import org.bson.BsonBinaryReader;
import org.bson.BsonBinaryWriter;
import org.bson.BsonType;
import org.bson.RawBsonDocument;
import org.bson.codecs.BsonDocumentCodec;
import org.bson.codecs.EncoderContext;
import org.bson.io.BasicOutputBuffer;

import java.nio.ByteBuffer;

/**
 * MongoDB BSON 读取器。从 BSON 字节数据或 {@link RawBsonDocument} 中读取字段并还原为 Entity。
 */
public class MongoReader implements Reader {

    private final BsonBinaryReader bsonReader;

    /** 从 BSON 字节数组构造 */
    public MongoReader(byte[] bsonBytes) {
        this.bsonReader = new BsonBinaryReader(ByteBuffer.wrap(bsonBytes));
    }

    /** 从 RawBsonDocument 构造（通过 Codec 获取字节，少量编解码开销） */
    public MongoReader(RawBsonDocument document) {
        this.bsonReader = new BsonBinaryReader(document.getByteBuffer().asNIO());
    }

    // ---- 文档结构 ----

    @Override
    public void readStartDocument() {
        bsonReader.readStartDocument();
    }

    @Override
    public void readEndDocument() {
        bsonReader.readEndDocument();
    }

    @Override
    public void readStartArray() {
        bsonReader.readStartArray();
    }

    @Override
    public void readEndArray() {
        bsonReader.readEndArray();
    }

    // ---- 迭代 ----

    @Override
    public int readBsonType() {
        BsonType type = bsonReader.readBsonType();
        return type.getValue();
    }

    @Override
    public String readName() {
        return bsonReader.readName();
    }

    // ---- 值读取 ----

    @Override
    public int readInt32() {
        return bsonReader.readInt32();
    }

    @Override
    public long readInt64() {
        return bsonReader.readInt64();
    }

    @Override
    public String readString() {
        return bsonReader.readString();
    }

    @Override
    public void skipValue() {
        bsonReader.skipValue();
    }
}

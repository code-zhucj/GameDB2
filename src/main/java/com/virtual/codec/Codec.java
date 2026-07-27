package com.virtual.codec;

public interface Codec {

    default void encode(Writer writer) {
        throw new RuntimeException("未实现序列化");
    }

    default void decode(Reader reader) {
        throw new RuntimeException("未实现反序列化");
    }
}

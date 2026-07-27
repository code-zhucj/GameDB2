package com.virtual.codec;

import com.virtual.entity.Entity;

public interface Writer {

    void put(String key, boolean b);

    void put(String key, byte b);

    void put(String key, short s);

    void put(String key, int i);

    void put(String key, long l);

    void put(String key, float f);

    void put(String key, double d);

    void put(String key, char c);

    void put(String key, String string);

    void put(String key, Entity e);

    void put(String key, Object e);
}

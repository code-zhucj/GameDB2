package com.virtual.codec;

import com.virtual.entity.Entity;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonNull;
import org.bson.BsonString;

import java.lang.constant.Constable;
import java.util.Collection;
import java.util.Map;

/**
 * @author zhuchuanji
 * @Description todo
 * @Create: 2026/7/20 1:37
 */
public class MongoWriter implements Writer {

    private final BsonDocument bsonDocument = new BsonDocument();

    @Override
    public void put(String key, Object o) {
        if (o == null) {
            bsonDocument.put(key, BsonNull.VALUE);
            return;
        }
        if (o instanceof Entity e) {
            put(key, e);
        } else if (o instanceof Constable c) {
            put(key, c);
        } else if (o instanceof Map<?, ?> m) {
            MongoWriter mongoWriter = new MongoWriter();
            m.forEach((k, v) -> mongoWriter.put(k.toString(), v));
            bsonDocument.put(key, mongoWriter.bsonDocument);
        } else if (o instanceof Collection<?> c) {
//            MongoWriter mongoWriter = new MongoWriter();
//            c.forEach();
//            bsonDocument.put(key, new BsonArray(c, false));
        } else {
            throw new RuntimeException(); // 序列化异常
        }
    }

    @Override
    public void put(String key, boolean b) {
        bsonDocument.put(key, BsonBoolean.valueOf(b));
    }

    @Override
    public void put(String key, byte b) {
        bsonDocument.put(key, new BsonInt32(b));

    }

    @Override
    public void put(String key, short s) {
        bsonDocument.put(key, new BsonInt32(s));

    }

    @Override
    public void put(String key, int i) {
        bsonDocument.put(key, new BsonInt32(i));
    }

    @Override
    public void put(String key, long l) {
        bsonDocument.put(key, new BsonInt64(l));
    }

    @Override
    public void put(String key, float f) {
        bsonDocument.put(key, new BsonDouble(f));
    }

    @Override
    public void put(String key, double d) {
        bsonDocument.put(key, new BsonDouble(d));
    }

    @Override
    public void put(String key, char c) {
        bsonDocument.put(key, new BsonString(String.valueOf(c)));
    }

    @Override
    public void put(String key, String string) {
        bsonDocument.put(key, new BsonString(string));
    }

    @Override
    public void put(String key, Entity e) {
        MongoWriter mongoWriter = new MongoWriter();
        e.encode(mongoWriter);
        bsonDocument.put(key, mongoWriter.bsonDocument);
    }
}

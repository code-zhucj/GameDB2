package com.virtual.persistent;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.virtual.TableDefine;
import com.virtual.Tables;
import com.virtual.codec.MongoReader;
import com.virtual.codec.MongoWriter;
import com.virtual.codec.Writer;
import org.bson.RawBsonDocument;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.pojo.PojoCodecProvider;

import java.util.function.Supplier;

/**
 * @author zhuchuanji
 * @Description Mongo 客户端
 * @Create: 2026/5/28 17:23
 */
public class MongoClient implements PersistentClient {

    private static final String database = "test"; // todo 暂定-》配置

    private final MongoDatabase mongoDatabase;
    private static final ReplaceOptions REPLACE_OPTIONS = new ReplaceOptions().upsert(true);

    public MongoClient() {
        com.mongodb.client.MongoClient mongoClient = MongoClients.create(MongoClientSettings
                .builder()
                .build());
        this.mongoDatabase = mongoClient.getDatabase(database);
    }


    @Override
    public <T extends TableDefine> TableHelper<T> getHelper(String tableName, Class<T> tableClass) {
        return new MongoTableHelper<>(tableName, tableClass);
    }

    private class MongoTableHelper<T extends TableDefine> implements TableHelper<T> {

        private final MongoCollection<RawBsonDocument> mongoCollection;
        private final Supplier<T> creator;

        public MongoTableHelper(String tableName, Class<T> tableClass) {
            this.mongoCollection = mongoDatabase.getCollection(tableName, RawBsonDocument.class);
            this.creator = Tables.creator(tableClass);
        }

        @Override
        public T select(Comparable<?> key) {
            return decode(mongoCollection.find(Filters.eq(key)).first());
        }

        private T decode(RawBsonDocument rawBsonDocument) {
            if (rawBsonDocument == null) {
                return null;
            }
            T t = creator.get();
            t.decode(new MongoReader(rawBsonDocument));
            return t;
        }

        @Override
        public void delete(Comparable<?> key) {
            mongoCollection.deleteOne(Filters.eq(key));
        }

        @Override
        public void insert(T t) {
            MongoWriter mongoWriter = new MongoWriter();
            t.encode(mongoWriter);
            insert(mongoWriter);
        }

        @Override
        public void update(T t) {
            MongoWriter mongoWriter = new MongoWriter();
            t.encode(mongoWriter);
            update(t.primaryKey(), mongoWriter);
        }

        @Override
        public void insert(Writer t) {
            mongoCollection.insertOne(((MongoWriter) t).toRawBsonDocument());
        }

        @Override
        public void update(Comparable<?> key, Writer t) {
            mongoCollection.replaceOne(Filters.eq(key), ((MongoWriter) t).toRawBsonDocument(), REPLACE_OPTIONS);
        }

        @Override
        public Iterable<T> selectByLimit(int cacheSize) {
            return mongoCollection.find().limit(cacheSize).map(this::decode);
        }
    }
}

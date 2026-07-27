package com.virtual.persistent;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.virtual.TableDefine;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.pojo.PojoCodecProvider;

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
                .codecRegistry(CodecRegistries
                        .fromProviders(PojoCodecProvider.builder().automatic(true).build()))
                .build());
        this.mongoDatabase = mongoClient.getDatabase(database);
    }


    @Override
    public <T extends TableDefine<?>> TableHelper<T> getHelper(String tableName, Class<T> tableClass) {
        return new MongoTableHelper<>(tableName, tableClass);
    }

    private class MongoTableHelper<T extends TableDefine<?>> implements TableHelper<T> {

        private final MongoCollection<T> mongoCollection;

        public MongoTableHelper(String tableName, Class<T> tableClass) {
            this.mongoCollection = mongoDatabase.getCollection(tableName, tableClass);
        }

        @Override
        public T select(Comparable<?> key) {
            return mongoCollection.find(Filters.eq(key)).first();
        }

        @Override
        public void delete(Comparable<?> key) {
            mongoCollection.deleteOne(Filters.eq(key));
        }

        @Override
        public void insert(T t) {
            mongoCollection.insertOne(t);
        }

        @Override
        public void update(T t) {
            mongoCollection.replaceOne(Filters.eq(t.primaryKey()), t, REPLACE_OPTIONS);
        }

        @Override
        public Iterable<T> selectByLimit(int cacheSize) {
            return mongoCollection.find().limit(cacheSize);
        }
    }
}

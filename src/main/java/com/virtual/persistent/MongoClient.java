package com.virtual.persistent;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.TransactionOptions;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.DeleteOneModel;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.InsertOneModel;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.WriteModel;
import com.virtual.TableDefine;
import com.virtual.Tables;
import com.virtual.codec.MongoReader;
import com.virtual.codec.MongoWriter;
import com.virtual.codec.Writer;
import org.bson.RawBsonDocument;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * @author zhuchuanji
 * @Description Mongo 客户端
 * @Create: 2026/5/28 17:23
 */
public class MongoClient implements PersistentClient {

    private static final String database = "test"; // todo 暂定-》配置

    private final com.mongodb.client.MongoClient mongoClient;
    private final MongoDatabase mongoDatabase;
    private static final ReplaceOptions REPLACE_OPTIONS = new ReplaceOptions().upsert(true);
    private static final TransactionOptions TX_OPTIONS = TransactionOptions.builder().build();

    /** 当前线程的事务 session，Flash 线程设置后，MongoTableHelper 隐式获取 */
    private static final ThreadLocal<ClientSession> currentSession = new ThreadLocal<>();


    public MongoClient() {
        this.mongoClient = MongoClients.create(MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString("mongodb://localhost"))
                .applyToConnectionPoolSettings(builder -> {
                    builder.maxSize(10)
                            .minSize(5)
                            .maxWaitTime(30_000, TimeUnit.MILLISECONDS);
                })
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
        public void batchWrite(List<BatchOp> ops) {
            if (ops.isEmpty()) {
                return;
            }
            List<WriteModel<RawBsonDocument>> models = new ArrayList<>(ops.size());
            for (BatchOp op : ops) {
                models.add(switch (op.type()) {
                    case DELETE -> new DeleteOneModel<>(Filters.eq(op.key()));
                    case INSERT -> new InsertOneModel<>(((MongoWriter) op.writer()).toRawBsonDocument());
                    case UPDATE -> new ReplaceOneModel<>(Filters.eq(op.key()), ((MongoWriter) op.writer()).toRawBsonDocument(), REPLACE_OPTIONS);
                });
            }
            ClientSession session = currentSession.get();
            if (session != null) {
                mongoCollection.bulkWrite(session, models);
            } else {
                mongoCollection.bulkWrite(models);
            }
        }

        @Override
        public Iterable<T> selectByLimit(int cacheSize) {
            return mongoCollection.find().limit(cacheSize).map(this::decode);
        }
    }

    @Override
    public void startTransaction() {
        ClientSession session = mongoClient.startSession();
        session.startTransaction(TX_OPTIONS);
        currentSession.set(session);
    }

    @Override
    public void commitTransaction() {
        ClientSession session = currentSession.get();
        if (session != null) {
            session.commitTransaction();
            session.close();
            currentSession.remove();
        }
    }

    @Override
    public void abortTransaction() {
        ClientSession session = currentSession.get();
        if (session != null) {
            session.abortTransaction();
            session.close();
            currentSession.remove();
        }
    }
}

package com.virtual;

import com.virtual.api.AutoPrimaryKey;
import com.virtual.api.Id;
import com.virtual.api.TableConfig;
import com.virtual.exception.GameDBException;
import com.virtual.persistent.BatchOp;
import com.virtual.persistent.Persistent;
import com.virtual.persistent.TableHelper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class Table<Entity extends TableDefine> implements TableHelper<Entity> {

    @Getter
    private final Class<Entity> tableClass;
    private Map<Comparable<?>, Record<Entity>> records;
    private boolean allCache;
    @Getter
    private String tableName;
    private int cacheSize;
    @Getter
    private TableConfig tableConfig;

    private final TableHelper<Entity> tableHelper;

    /**
     * long 主键自增计数器，起服时根据库中最大主键校正
     */
    private final AtomicLong maxId = new AtomicLong();
    /**
     * 自增主键处理器，null 表示不自增
     */
    private AutoPrimaryKey<?> autoKey;
    /**
     * 是否使用内置 long 自增方案（仅该方案需要校正 maxId）
     */
    private boolean builtInLongAutoKey = false;


    public Table(Class<Entity> tableClass) {
        this.tableClass = tableClass;
        initTableInfo();
        initAutoKey();
        tableHelper = Persistent.INSTANCE.getTableHelper(tableName, tableClass);
    }

    public void initTableInfo() {
        this.tableConfig = this.tableClass.getAnnotation(TableConfig.class);
        tableName = tableConfig.value();
        if (tableName.isBlank()) {
            tableName = this.tableClass.getSimpleName();
        }
        cacheSize = tableConfig.cacheSize();
        if (cacheSize == Integer.MAX_VALUE) {
            records = new AllCache<>();
            allCache = true;
        } else {
            records = new LRUCache<>(cacheSize);
            allCache = false;
        }
    }

    /**
     * 只允许开服的时候调用一次,这里应该只对需要全缓存的表进行加载
     */
    public void loadFromDB() {
        Logic loadData = () -> {
            Iterable<? extends Entity> iterator = tableHelper.selectByLimit(cacheSize);
            iterator.forEach(entity -> records.put(entity.primaryKey(), new Record<>(Record.State.DB, this, entity)));
            log.info("table {} load from DB, count: {}", tableName, records.size());
            return Logic.State.SUCCESS;
        };
        loadData.submit();
    }

    /**
     * 起服时根据库中最大主键校正自增计数器，只对内置 long 自增方案生效
     */
    public void initMaxId() {
        if (!builtInLongAutoKey) {
            return;
        }
        Comparable<?> max = tableHelper.maxKey();
        if (max instanceof Number n) {
            maxId.set(n.longValue());
        }
    }

    @Override
    public Comparable<?> maxKey() {
        return tableHelper.maxKey();
    }

    /**
     * 根据 @Id 注解初始化自增主键处理器：
     * 默认方案（TableAutoKey）仅支持 long 主键；其他类型需自定义 AutoPrimaryKey
     */
    private void initAutoKey() {
        Field idField = null;
        for (Field field : tableClass.getDeclaredFields()) {
            if (field.getAnnotation(Id.class) != null) {
                idField = field;
                break;
            }
        }
        if (idField == null) {
            return;
        }
        Id id = idField.getAnnotation(Id.class);
        Class<? extends AutoPrimaryKey<?>> gen = id.autoKeyGen();
        boolean isLong = idField.getType() == long.class || idField.getType() == Long.class;
        if (gen == AutoPrimaryKey.TableAutoKey.class) {
            if (isLong) {
                this.autoKey = AutoPrimaryKey.TableAutoKey.of(maxId::incrementAndGet);
                this.builtInLongAutoKey = true;
            }
        } else {
            this.autoKey = newInstance(gen);
        }
    }

    private AutoPrimaryKey<?> newInstance(Class<? extends AutoPrimaryKey<?>> gen) {
        try {
            return gen.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new GameDBException("实例化自增主键处理器失败: " + gen.getName(), e);
        }
    }

    /**
     * 主键是否自动生成由自增处理器判断（默认空值或数值 0 时生成），手动设置的主键优先
     */
    private void assignAutoKey(Entity entity) {
        if (autoKey == null) {
            return;
        }
        Comparable<?> pk = entity.primaryKey();
        if (autoKey.shouldGenerate(pk)) {
            entity.setPrimaryKey(autoKey.nextKey());
        }
    }

    @Override
    public void insert(Entity entity) {
        checkEntity(entity);
        assignAutoKey(entity);
        validPrimaryKey(entity.primaryKey());
        TransactionImpl transaction = TransactionImpl.checkAndGet();
        RowLock rowLock = Locks.getLock(new LockKey(getTableName(), entity.primaryKey()));
        Record<Entity> recordCopy = transaction.getRecord(rowLock.lockKey);
        if (recordCopy != null) {
            if (recordCopy.getState() == Record.State.NULL) {
                recordCopy.setState(Record.State.INSERT);
                recordCopy.setEntity(entity);
            } else {
                throw new GameDBException("主键重复 key:" + entity.primaryKey()); // 主键重复
            }
        } else {
            rowLock.readLock().lock();
            try {
                Record<Entity> tRecord = this.records.get(entity.primaryKey());
                if (tRecord == null) {
                    if (allCache) {
                        tRecord = new Record<>(Record.State.NULL, this, entity.primaryKey());
                    } else {
                        Entity select = tableHelper.select(entity.primaryKey());
                        if (select != null) {
                            this.records.put(entity.primaryKey(), new Record<>(Record.State.DB, this, select));
                            throw new GameDBException("主键重复 key:" + entity.primaryKey()); // 主键重复
                        }
                        this.records.put(entity.primaryKey(), tRecord = new Record<>(Record.State.NULL, this, entity.primaryKey()));
                    }
                } else if (tRecord.getEntity() != null) {
                    throw new GameDBException("主键重复 key:" + entity.primaryKey()); // 主键重复
                }
                Record<Entity> copy = tRecord.copy();
                copy.setState(Record.State.INSERT);
                copy.setEntity(entity);
                copy.bindLock(rowLock);
                transaction.recorded(rowLock.lockKey, copy);
                transaction.markModify();
            } finally {
                rowLock.readLock().unlock();
            }
        }
    }

    @Override
    public void update(Entity entity) {
        checkEntity(entity);
        validPrimaryKey(entity.primaryKey());
        // 这里更像是用一个新的entity去覆盖旧的entity,实际业务使用中应该比较少,都是直接select后直接在对象上修改
        TransactionImpl transaction = TransactionImpl.checkAndGet();
        RowLock rowLock = Locks.getLock(new LockKey(getTableName(), entity.primaryKey()));
        Record<Entity> recordCopy = transaction.getRecord(rowLock.lockKey);
        if (recordCopy == null) {
            rowLock.readLock().lock();
            try {
                Record<Entity> record = this.records.get(entity.primaryKey());
                if (record == null) {
                    if (allCache) {
                        record = new Record<>(Record.State.NULL, this, entity.primaryKey());
                    } else {
                        Entity select = tableHelper.select(entity.primaryKey());
                        record = select == null ? new Record<>(Record.State.NULL, this, entity.primaryKey()) : new Record<>(Record.State.DB, this, select);
                        this.records.put(entity.primaryKey(), record);
                    }
                }
                Record<Entity> copy = record.copy();
                copy.bindLock(rowLock);
                transaction.recorded(rowLock.lockKey, recordCopy = copy);
            } finally {
                rowLock.readLock().unlock();
            }
        }
        if (recordCopy.getState() != Record.State.INSERT) {
            recordCopy.setState(Record.State.UPDATE);
        }
        recordCopy.setEntity(entity);
        transaction.markModify();
    }

    @Override
    public Iterable<Entity> selectByLimit(int cacheSize) {
        return tableHelper.selectByLimit(cacheSize);
    }

    @Override
    public void batchWrite(List<BatchOp> ops) {
        // 内存 Table 不直接负责持久化，由 MongoTableHelper 处理
        throw new UnsupportedOperationException("Table.batchWrite is not supported, use persistent layer");
    }


    @Override
    public Entity select(Comparable<?> id) {
        validPrimaryKey(id);
        TransactionImpl transaction = TransactionImpl.checkAndGet();
        RowLock rowLock = Locks.getLock(new LockKey(getTableName(), id));
        Record<Entity> recordCopy = transaction.getRecord(rowLock.lockKey);
        if (recordCopy == null) {
            rowLock.readLock().lock();
            try {
                Record<Entity> tRecord = this.records.get(id);
                if (tRecord == null) {
                    if (allCache) {
                        tRecord = new Record<>(Record.State.NULL, this, id);
                    } else {
                        // 穿透到DB中查,如果还是没有,则插入一个空节点,避免每次都穿透
                        Entity select = tableHelper.select(id);
                        tRecord = select == null ? new Record<>(Record.State.NULL, this, id) : new Record<>(Record.State.DB, this, select);
                        this.records.put(id, tRecord);
                    }
                }
                Record<Entity> copy = tRecord.copy();
                copy.bindLock(rowLock);
                transaction.recorded(rowLock.lockKey, recordCopy = copy);
            } finally {
                rowLock.readLock().unlock();
            }
        }
        return recordCopy.getState() == Record.State.DELETE ? null : recordCopy.getEntity();
    }

    @Override
    public void delete(Comparable<?> id) {
        validPrimaryKey(id);
        TransactionImpl transaction = TransactionImpl.checkAndGet();
        RowLock rowLock = Locks.getLock(new LockKey(getTableName(), id));
        Record<Entity> recordCopy = transaction.getRecord(rowLock.lockKey);
        if (recordCopy != null) {
            recordCopy.setState(Record.State.DELETE);
        } else {
            rowLock.readLock().lock();
            try {
                Record<Entity> tRecord = this.records.get(id);
                Record<Entity> copy = tRecord == null ? new Record<>(Record.State.DELETE, this, id) : tRecord.copy();
                copy.bindLock(rowLock);
                copy.setState(Record.State.DELETE);
                transaction.recorded(rowLock.lockKey, copy);
                transaction.markModify();
            } finally {
                rowLock.readLock().unlock();
            }
        }
    }

    private void checkEntity(Entity e) {
        if (Tables.getTable(e.getClass()) != null) {
            throw new GameDBException("请使用com.virtual.Tables.create创建的entity进行操作");
        }
    }


    private void validPrimaryKey(Comparable<?> id) {
        if (id == null) {
            throw new GameDBException("主键为null");
        }
    }

    Record<Entity> getRecord(Comparable<?> primaryKey) {
        return records.get(primaryKey);
    }

    @SuppressWarnings("unchecked")
    void putRecord(Record<?> record) {
        records.put(record.getPrimaryKey(), (Record<Entity>) record);
    }

    void removeRecord(Comparable<?> key) {
        records.remove(key);
    }
}

package com.virtual;

import com.virtual.api.T;
import com.virtual.persistent.Persistent;
import com.virtual.persistent.TableHelper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class Table<Entity extends TableDefine> implements TableHelper<Entity> {

    @Getter
    private final Class<Entity> tableClass;
    private final Map<Comparable<?>, Record<Entity>> records = new ConcurrentHashMap<>();
    @Getter
    private String tableName;
    private int cacheSize;

    private final TableHelper<Entity> tableHelper;
    private final ReentrantLock tableLock = new ReentrantLock();


    public Table(Class<Entity> tableClass) {
        this.tableClass = tableClass;
        initTableInfo();
        tableHelper = Persistent.INSTANCE.getTableHelper(tableName, tableClass);
    }

    public void initTableInfo() {
        T t = this.tableClass.getAnnotation(T.class);
        tableName = t.value();
        if (tableName.isBlank()) {
            tableName = this.tableClass.getSimpleName();
        }
        cacheSize = t.cacheSize();
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

    @Override
    public void insert(Entity entity) {
        // todo 这里的主键先让用户自己设置,后续补充自增主键
        validPrimaryKey(entity.primaryKey());
        TransactionImpl transaction = TransactionImpl.checkAndGet();
        RowLock rowLock = Locks.getLock(new LockKey(getTableName(), entity.primaryKey()));
        Record<Entity> recordCopy = transaction.getRecord(rowLock.lockKey);
        if (recordCopy != null) {
            if (recordCopy.getState() == Record.State.NULL) {
                recordCopy.setState(Record.State.INSERT);
                recordCopy.setEntity(entity);
            } else {
                throw new GameDBException(); // 主键重复
            }
        } else {
            rowLock.readLock().lock();
            try {
                Record<Entity> tRecord = this.records.get(entity.primaryKey());
                if (tRecord == null) {
                    Entity select = tableHelper.select(entity.primaryKey());
                    if (select != null) {
                        this.records.put(entity.primaryKey(), new Record<>(Record.State.DB, this, select));
                        throw new GameDBException(); // 主键重复
                    }
                    this.records.put(entity.primaryKey(), tRecord = new Record<>(Record.State.NULL, this, entity.primaryKey()));
                } else if (tRecord.getEntity() != null) {
                    throw new GameDBException(); // 主键重复
                }
                Record<Entity> copy = tRecord.copy();
                copy.setState(Record.State.INSERT);
                copy.setEntity(entity);
                copy.bindLock(rowLock);
                transaction.recorded(rowLock.lockKey, copy);
            } finally {
                rowLock.readLock().unlock();
            }
        }
    }

    @Override
    public void update(Entity entity) {
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
                    Entity select = tableHelper.select(entity.primaryKey());
                    record = select == null ? new Record<>(Record.State.NULL, this, entity.primaryKey()) : new Record<>(Record.State.DB, this, select);
                    this.records.put(entity.primaryKey(), record);
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
    }

    @Override
    public Iterable<Entity> selectByLimit(int cacheSize) {
        return tableHelper.selectByLimit(cacheSize);
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
                    // 穿透到DB中查,如果还是没有,则插入一个空节点,避免每次都穿透
                    Entity select = tableHelper.select(id);
                    tRecord = select == null ? new Record<>(Record.State.NULL, this, id) : new Record<>(Record.State.DB, this, select);
                    this.records.put(id, tRecord);
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
            } finally {
                rowLock.readLock().unlock();
            }
        }
    }


    private void validPrimaryKey(Comparable<?> id) {
        if (id == null) {
            throw new GameDBException();
        }
    }

    Record<Entity> getRecord(Comparable<?> primaryKey) {
        return records.get(primaryKey);
    }

    void putRecord(Record<?> record) {
        records.put(record.getPrimaryKey(), (Record<Entity>) record);
    }
}

package com.virtual;

import com.virtual.Log.EntityLog;
import com.virtual.Log.IntLog;
import com.virtual.Log.Log;
import com.virtual.Log.StringLog;
import com.virtual.Log.VisitEntityLog;
import com.virtual.codec.Reader;
import com.virtual.codec.Writer;
import com.virtual.entity.VisitMapEntity;

import java.util.Map;

/**
 * 整个类均由代码生成
 */
public class _User extends User {

    @Override
    public int getUserId() {
        TransactionImpl transaction = TransactionImpl.checkAndGet();
        Log<Integer> log = transaction.getLog(this, "userId");
        return log == null ? super.getUserId() : log.getValue();
    }

    @Override
    public String getName() {
        TransactionImpl transaction = TransactionImpl.checkAndGet();
        Log<String> log = transaction.getLog(this, "name");
        return log == null ? super.getName() : log.getValue();
    }

    @Override
    public Bag getBag() {
        TransactionImpl transaction = TransactionImpl.checkAndGet();
        Log<Bag> log = transaction.getLog(this, "bag");
        return log == null ? super.getBag() : log.getValue();
    }

    @Override
    public Map<Integer, Integer> getItems() {
        TransactionImpl transaction = TransactionImpl.checkAndGet();
        Log<Map<Integer, Integer>> log = transaction.getLog(this, "items");
        if (log != null) {
            return log.getValue();
        } else if (super.getItems() == null) {
            return null;
        } else {
            VisitMapEntity<Integer, Integer> mapEntity = new VisitMapEntity<>(super.getItems());
            mapEntity.setRoot(this.getRoot());
            transaction.log(this, "items", new VisitEntityLog<>(mapEntity, super::setItems));
            return mapEntity;
        }

    }

    @Override
    public void setUserId(int userId) {
        if (isInit()) {
            super.setUserId(userId);
        } else {
            TransactionImpl transaction = TransactionImpl.checkAndGet();
            transaction.log(this, "userId", new IntLog(userId, super::setUserId));
        }
    }

    @Override
    public void setName(String name) {
        if (isInit()) {
            super.setName(name);
        } else {
            TransactionImpl transaction = TransactionImpl.checkAndGet();
            transaction.log(this, "name", new StringLog(name, super::setName));
        }
    }

    @Override
    public void setBag(Bag bag) {
        bag.checkValid(this.getRoot());
        bag.setRoot(this.getRoot());
        if (isInit()) {
            super.setBag(bag);
        } else {
            TransactionImpl transaction = TransactionImpl.checkAndGet();
            transaction.log(this, "bag", new EntityLog<>(bag, super::setBag));
        }
    }

    @Override
    public void setItems(Map<Integer, Integer> items) {
        VisitMapEntity<Integer, Integer> mapEntity = new VisitMapEntity<>(items);
        mapEntity.setRoot(this.getRoot());
        if (isInit()) {
            super.setItems(items);
        } else {
            TransactionImpl transaction = TransactionImpl.checkAndGet();
            transaction.log(this, "items", new VisitEntityLog<>(mapEntity, super::setItems));
        }
    }

    @Override
    public void encode(Writer writer) {
        writer.put("userId", super.getUserId());
        writer.put("name", super.getName());
        writer.put("bag", super.getBag());
        writer.put("items", super.getItems());
    }

    @Override
    public void decode(Reader reader) {
        super.setUserId(reader.get("userId"));
        super.setName(reader.get("name"));
        super.setBag(reader.get("bag"));
        super.setItems(reader.get("items"));
    }
}

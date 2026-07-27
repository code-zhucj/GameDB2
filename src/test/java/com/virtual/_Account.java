package com.virtual;

import com.virtual.Log.IntLog;
import com.virtual.Log.Log;

/**
 * @author zhuchuanji
 * @Description todo
 * @Create: 2026/6/21 17:52
 */
public class _Account extends Account {

    @Override
    public int getId() {
        TransactionImpl transaction = TransactionImpl.checkAndGet();
        Log<Integer> log = transaction.getLog(this, "id");
        return log == null ? super.getId() : log.getValue();
    }

    @Override
    public int getCount() {
        TransactionImpl transaction = TransactionImpl.checkAndGet();
        Log<Integer> log = transaction.getLog(this, "count");
        return log == null ? super.getCount() : log.getValue();
    }

    @Override
    public void setId(int id) {
        if (isInit()) {
            super.setId(id);
        } else {
            TransactionImpl transaction = TransactionImpl.checkAndGet();
            transaction.log(this, "id", new IntLog(id, super::setId));
        }
    }

    @Override
    public void setCount(int count) {
        if (isInit()) {
            super.setCount(count);
        } else {
            TransactionImpl transaction = TransactionImpl.checkAndGet();
            transaction.log(this, "count", new IntLog(count, super::setCount));
        }
    }
}

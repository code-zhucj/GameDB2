package com.virtual.persistent;

import com.virtual.TableDefine;

/**
 * @author zhuchuanji
 * @Description 持久化客户端
 * @Create: 2026/5/28 17:23
 */
public interface PersistentClient {

    <T extends TableDefine> TableHelper<T> getHelper(String tableName, Class<T> tableClass);

    /** 开启事务 */
    void startTransaction();

    /** 提交事务 */
    void commitTransaction();

    /** 回滚事务 */
    void abortTransaction();
}

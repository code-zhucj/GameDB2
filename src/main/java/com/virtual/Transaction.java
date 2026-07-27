package com.virtual;

/**
 * 事物接口,提供了事物提交的整套链路
 */
public interface Transaction {

    void commit();

    void rollback();

}

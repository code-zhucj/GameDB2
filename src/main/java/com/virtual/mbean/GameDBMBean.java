package com.virtual.mbean;

/**
 * GameDB 监控管理接口，暴露事务数量、重试率、快照耗时、Flash 吞吐量、队列深度等指标
 *
 * @author zhuchuanji
 */
public interface GameDBMBean {

    /** 在途事务数 */
    long getTransactionActiveCount();

    /** 累计提交事务数 */
    long getTransactionTotalCount();

    /** 累计重试事件次数 */
    long getRetryCount();

    /** 重试率 = 累计重试次数 / 累计提交事务数 */
    double getRetryRate();

    /** 最近一次快照耗时(ms) */
    long getLastSnapshotCostMs();

    /** 快照执行次数 */
    long getSnapshotCount();

    /** 累计落库记录数 */
    long getFlashTotalRecords();

    /** 累计落库吞吐量(条/秒) */
    double getFlashThroughput();

    /** 最近一批落库记录数 */
    long getLastFlashRecords();

    /** 最近一批落库耗时(ms) */
    long getLastFlashCostMs();

    /** 事务线程池队列深度 */
    int getTransactionQueueDepth();

    /** Flash 落库队列深度 */
    int getFlashQueueDepth();

    /** 快照待处理队列深度 */
    int getSnapshotQueueDepth();
}

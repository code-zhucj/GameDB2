package com.virtual;

import lombok.Data;

/**
 * @author zhuchuanji
 * @Description GameDB 的基础配置
 * @Create: 2026/8/9 23:11
 */
@Data
public class GameDBConfig {
    /**
     * 事物线程数
     */
    private int transactionThreadNum = Runtime.getRuntime().availableProcessors();
    /**
     * 最大事物数量
     */
    private int maxTransactionCount = 100_000;
    /**
     * 事物默认重试次数
     */
    private int retryNum = 3;

    /**
     * 事物重复执行,用于在开发环境下模拟事物重试,这个可以更容易发现一些不可重复执行的操作,比如修改logic中的变量,重复IO等
     */
    private boolean doubleExec = false;
    /**
     * 持久化数据库类型，目前只支持Mongo
     */
    private PersistentDB persistentDB;
    /**
     * 快照周期
     */
    private int snapshotPeriod = 1000;

    private Database database;

    public enum PersistentDB {
        MONGO,
    }

    @Data
    public static class Database {
        private String url;
        private String username;
        private String password;
        private String useDatabase;
        private String authDatabase;
        private int connectionTimeout = 30_000;
        private int connectTimeout = 60_000;
        private int readTimeout = 30_000;
    }
}

package com.virtual.mbean;

import com.virtual.TransactionImpl;
import com.virtual.persistent.Persistent;
import lombok.extern.slf4j.Slf4j;

import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;
import java.lang.management.ManagementFactory;

/**
 * GameDB 监控 MBean 实现，指标来源于 {@link TransactionImpl} 与 {@link Persistent} 单例
 *
 * @author zhuchuanji
 */
@Slf4j
public class GameDBMonitor implements GameDBMBean {

    private static final String OBJECT_NAME = "com.virtual:type=GameDBMonitor";

    /** 注册到平台 MBeanServer，幂等 */
    public static void register() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            server.registerMBean(new StandardMBean(new GameDBMonitor(), GameDBMBean.class),
                    new ObjectName(OBJECT_NAME));
            log.info("GameDB MBean 已注册: {}", OBJECT_NAME);
        } catch (InstanceAlreadyExistsException e) {
            log.warn("GameDB MBean 已存在, 忽略重复注册: {}", OBJECT_NAME);
        } catch (Exception e) {
            log.error("GameDB MBean 注册失败", e);
        }
    }

    @Override
    public long getTransactionActiveCount() {
        return TransactionImpl.getActiveTransactionCount();
    }

    @Override
    public long getTransactionTotalCount() {
        return TransactionImpl.getTotalTransactionCount();
    }

    @Override
    public long getRetryCount() {
        return TransactionImpl.getRetryCount();
    }

    @Override
    public double getRetryRate() {
        long total = getTransactionTotalCount();
        return total == 0 ? 0 : (double) getRetryCount() / total;
    }

    @Override
    public long getLastSnapshotCostMs() {
        return Persistent.INSTANCE.getSnapshot().getLastCostMs();
    }

    @Override
    public long getSnapshotCount() {
        return Persistent.INSTANCE.getSnapshot().getSnapshotCount();
    }

    @Override
    public long getFlashTotalRecords() {
        return Persistent.INSTANCE.getFlash().getTotalRecords();
    }

    @Override
    public double getFlashThroughput() {
        long totalMs = Persistent.INSTANCE.getFlash().getTotalCostMs();
        return totalMs == 0 ? 0 : getFlashTotalRecords() * 1000.0 / totalMs;
    }

    @Override
    public long getLastFlashRecords() {
        return Persistent.INSTANCE.getFlash().getLastRecords();
    }

    @Override
    public long getLastFlashCostMs() {
        return Persistent.INSTANCE.getFlash().getLastCostMs();
    }

    @Override
    public int getTransactionQueueDepth() {
        return TransactionImpl.getTransactionQueueDepth();
    }

    @Override
    public int getFlashQueueDepth() {
        return Persistent.INSTANCE.getFlash().getQueueDepth();
    }

    @Override
    public int getSnapshotQueueDepth() {
        return Persistent.INSTANCE.getSnapshot().getQueueDepth();
    }
}

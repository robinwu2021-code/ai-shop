package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.api.fulfillment.MallRecoveryFacade;
import ai.neargo.commerce.api.offline.OfflinePurchaseFacade;
import ai.neargo.job.api.*;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.pay.service.PlatformMoneyHandoffService;
import ai.neargo.shop.spi.pay.PlatformPaymentRecoveryPort;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Separate cash-ledger and commercial rotations; a poison handoff cannot monopolize either scan.
 */
@Component
@ConditionalOnProperty(
        name = {
            "shop.job.enabled",
            "commerce.enabled",
            "commerce.mall.payment.enabled",
            "commerce.mall.recovery.enabled"
        },
        havingValue = "true")
public class MallRecoveryJob implements JobHandler {
    private final MallRecoveryFacade core;
    private final PlatformMoneyHandoffService handoffs;
    private final PlatformPaymentRecoveryPort recovery;
    private final JobSupport jobs;
    private final int batchSize;
    private long orderCursor;
    private String handoffCursor;
    private String offlineCursor;
    private OfflinePurchaseFacade offline;

    public MallRecoveryJob(
            MallRecoveryFacade core,
            PlatformMoneyHandoffService handoffs,
            PlatformPaymentRecoveryPort recovery,
            JobSupport jobs,
            @Value("${commerce.mall.recovery.batch-size:50}") int batchSize) {
        if (batchSize < 1 || batchSize > 100)
            throw new IllegalArgumentException("Mall recovery batch-size must be 1..100");
        this.core = core;
        this.handoffs = handoffs;
        this.recovery = recovery;
        this.jobs = jobs;
        this.batchSize = batchSize;
    }

    /** Present only when the offline workflow is assembled. */
    @Autowired(required = false)
    void setOffline(OfflinePurchaseFacade offline) {
        this.offline = offline;
    }

    public String name() {
        return "commerce-mall-recovery";
    }

    @Scheduled(cron = "${commerce.mall.recovery.cron:15 */2 * * * *}")
    @SchedulerLock(name = "commerce-mall-recovery", lockAtMostFor = "PT90S")
    public void tick() {
        jobs.run(name(), () -> run(null).detail());
    }

    @Bean
    public JobDeclaration mallRecoveryDeclaration() {
        return new JobDeclaration(
                name(),
                "商城付款与履约恢复",
                "按原操作恢复账本交接、库存、权益与退款；未知保留",
                "shop-app",
                "15 */2 * * * *",
                true,
                90,
                120,
                true,
                true);
    }

    public synchronized JobResult run(JobInvocation invocation) {
        int checked = 0, failed = 0, orders = 0;
        try {
            var pending = handoffs.pending(handoffCursor, batchSize);
            for (var handoff : pending) {
                checked++;
                try {
                    recovery.recover(handoff.paymentNo());
                } catch (RuntimeException retry) {
                    failed++;
                }
            }
            handoffCursor = pending.size() < batchSize ? null : pending.getLast().paymentNo();
        } catch (RuntimeException retry) {
            failed++;
        }
        try {
            var result = core.recover(new MallRecoveryFacade.Batch(orderCursor, batchSize));
            orderCursor = result.endOfScan() ? 0 : result.nextOrderId();
            orders = result.visited();
            failed += result.failed();
        } catch (RuntimeException retry) {
            failed++;
        }
        int offlineOrders = 0;
        if (offline != null) {
            try {
                var sweep = offline.recoverPending("MAIN", offlineCursor, batchSize);
                offlineCursor = sweep.nextOrderNo();
                offlineOrders = sweep.visited();
                failed += sweep.failed();
            } catch (RuntimeException retry) {
                failed++;
            }
        }
        String detail =
                "账本交接检查%d，订单检查%d，线下核款恢复%d，待重试%d"
                        .formatted(checked, orders, offlineOrders, failed);
        return failed > 0
                ? JobResult.failed(detail, "MALL_RECOVERY_PENDING")
                : JobResult.ok(detail);
    }
}

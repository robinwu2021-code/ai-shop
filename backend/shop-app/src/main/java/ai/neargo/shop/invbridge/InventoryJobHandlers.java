package ai.neargo.shop.invbridge;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.inventory.job.InvDailySnapshotJob;
import ai.neargo.shop.inventory.job.InvOutboxDispatchJob;
import ai.neargo.shop.inventory.job.ReservationExpiryJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 把进销存那三个后台任务接到**独立调度器**上。
 *
 * <h2>为什么需要这个类</h2>
 *
 * 三个任务本来就写好了，也都挂着 {@code @Scheduled}。但生产上它们
 * <b>一次都没跑过</b>（2026-09-02 查实）：
 *
 * <pre>
 * inv_outbox        212 行，全部 PENDING，SENT 0 行
 * retry_count       max = 0        ← 连投都没投过，不是投失败
 * 事件跨度           2026-08-27 18:11 ~ 2026-08-30 22:17
 * </pre>
 *
 * 两道门各自看都合理，合起来就是没人跑：
 * <ul>
 *   <li>{@code @EnableScheduling} 挂着 {@code @Profile("worker")}（{@code SchedulingConfig}），
 *       而生产的 profile 是 {@code api,ops} —— <b>{@code @Scheduled} 连解析都不会发生</b></li>
 *   <li>另一条路是独立调度器按 {@code job_definition} 打进来，而进那张表要靠
 *       {@link JobDeclaration} bean —— 本域三个任务<b>一个都没声明</b></li>
 * </ul>
 *
 * {@code InventoryReconJob} 声明了，所以 {@code inv-recon} 在表里。
 * <b>同一个坑修过一次，剩下三个被落下了</b> —— 而它没有任何症状：
 * 任务类在、注解在、开关也开着，只是没有任何东西会去读那个注解。
 *
 * <h2>为什么放在 invbridge 而不是 shop-inventory 里</h2>
 *
 * {@code shop-inventory} <b>不依赖 {@code shop-job-api}</b>，这是有意的：那个模块要能
 * 独立交付给没有本平台调度器的客户。把 {@code JobHandler} 实现放进去等于给它加一个
 * 平台侧依赖。{@code InventoryReconJob} 当初放在这里就是这个原因，本类照办。
 *
 * <h2>三个任务的实际重要性并不一样</h2>
 *
 * <ul>
 *   <li><b>预留回收最危险</b>：不跑的话未付款订单的预留永不回收，那批货被永久占着，
 *       商家看到的是「有货卖不出去」。今天线上 0 预留，所以还没出事 —— <b>是没有生意，不是没有缺陷</b></li>
 *   <li><b>事件投递</b>：212 条积压。平台侧今天<b>没有 {@code INVENTORY} 聚合的消费者</b>，
 *       所以补投出去也没人接，低危；但切 G3 之后它是主链路</li>
 *   <li><b>日快照</b>：不跑时报表回退成扫流水，设计里认了这条</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
/*
 * **三个都要点名。** 原本只点了 InvOutboxDispatchJob —— 三个 job 挂的是同一个
 * @ConditionalOnInventory，实际一起在或一起不在，所以运行时不会出事；但写成
 * 「依赖三个、只声明一个」的话，哪天有谁改了其中一个的条件，这里就静默落空了。
 */
@ConditionalOnBean({ InvOutboxDispatchJob.class, ReservationExpiryJob.class,
        InvDailySnapshotJob.class })
public class InventoryJobHandlers {

    private final InvOutboxDispatchJob outbox;
    private final ReservationExpiryJob expiry;
    private final InvDailySnapshotJob snapshot;

    public InventoryJobHandlers(InvOutboxDispatchJob outbox, ReservationExpiryJob expiry,
                                InvDailySnapshotJob snapshot) {
        this.outbox = outbox;
        this.expiry = expiry;
        this.snapshot = snapshot;
    }

    // ── 事件投递 ──────────────────────────────────────────────────────────
    @Bean
    public JobHandler invOutboxHandler() {
        return new Delegating("inv-outbox-dispatch", outbox::dispatchOnce);
    }

    /**
     * <b>5 秒一轮</b>，与类里 {@code @Scheduled} 的默认 cron 一致 ——
     * 两处写不同的值，将来看到的人不知道哪个在生效。
     */
    @Bean
    public JobDeclaration invOutboxDeclaration() {
        return new JobDeclaration("inv-outbox-dispatch", "进销存事件投递",
                "把 inv_outbox 里的事件转进平台 outbox。**没有订阅者时不标已发** —— "
                        + "事件堆在表里看得见，而不是被当作发过了悄悄丢掉",
                "shop-inventory", "*/5 * * * * *", true, 60, 120, true,
                // **不是每轮都记**：5 秒一轮，全记的话运行记录一天两万条，
                // 而其中绝大多数是「pending=0」。JobSupport 那边的口径也是这样
                false);
    }

    // ── 预留回收 ──────────────────────────────────────────────────────────
    @Bean
    public JobHandler invReservationExpiryHandler() {
        return new Delegating("inv-reservation-expiry", expiry::expireOnce);
    }

    /** 每分钟一次：预留的 TTL 以分钟计，扫得比它疏就等于 TTL 名存实亡。 */
    @Bean
    public JobDeclaration invReservationExpiryDeclaration() {
        return new JobDeclaration("inv-reservation-expiry", "进销存预留回收",
                "回收到期未提交的预留。**不跑的话未付款订单会把货永久占住** —— "
                        + "商家看到的是「有货卖不出去」，而库存数字看上去毫无问题",
                "shop-inventory", "0 * * * * *", true, 300, 300, true, false);
    }

    // ── 日快照 ────────────────────────────────────────────────────────────
    @Bean
    public JobHandler invSnapshotHandler() {
        return new Delegating("inv-daily-snapshot", snapshot::buildYesterday);
    }

    @Bean
    public JobDeclaration invSnapshotDeclaration() {
        return JobDeclaration.daily("inv-daily-snapshot", "进销存日快照",
                "给昨天的余额建一份快照，报表按它出而不必每次扫全量流水。"
                        + "不跑不会出错，只是报表变慢",
                "shop-inventory", "0 30 3 * * *");
    }

    /**
     * 一个 handler 就是「名字 + 一段返回人话的逻辑」。
     *
     * <p><b>异常要收成 {@code failed} 不能抛出去</b>（{@link JobHandler} 的第二条约定）：
     * 抛出去会变成 HTTP 5xx，调度器只能记成「调不通」，
     * 而那与「跑了但失败了」在排查时是两件事。
     */
    private record Delegating(String name, Supplier<String> work)
            implements JobHandler {

        @Override
        public JobResult run(JobInvocation invocation) {
            try {
                return JobResult.ok(work.get());
            } catch (RuntimeException e) {
                return JobResult.failed(e.getClass().getSimpleName() + ": " + e.getMessage(),
                        "UNEXPECTED");
            }
        }
    }
}

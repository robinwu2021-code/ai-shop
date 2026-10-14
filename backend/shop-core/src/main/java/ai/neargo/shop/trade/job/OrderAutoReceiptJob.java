package ai.neargo.shop.trade.job;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.trade.service.OrderService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 发货满 N 天自动确认收货（TDD-快递100商家寄件 §9 · B 批）。
 *
 * <p><b>发货之后没有任何东西推动订单前进。</b> {@code trade/job/} 下原本只有三个任务：
 * 未付款关单、售后超时、退款重试 —— 发出去的单停在 {@code FULFILLING}，
 * 直到买家自己想起来点「确认收货」。而结算要等 {@code COMPLETED}
 * （{@code SettleReadiness#completedAt}），于是：
 *
 * <pre>
 *   买家不点          →  子单永远 FULFILLING
 *   订单不 COMPLETED  →  结算单永远生不出来
 *   结算单没有        →  商家的货款永远提不出来
 * </pre>
 *
 * 全程不报错。商家看到的是「钱怎么一直没到」，而没有任何东西指向
 * 「自动确认收货这个任务从来没被写出来」。与 {@link OrderAutoCloseJob} 头部记的
 * 是同一个形状：能力缺的是调度器，而缺调度器在测试里完全不可见 ——
 * 测试都是自己手动调 {@code confirmReceipt} 的。
 *
 * <p><b>天数按行业惯例</b>：淘宝/拼多多通行的是「签收后 7 天」，而签收回传要等
 * 轨迹订阅接上（§9 E 批，{@code ful_shipment_trace} 现在一行都没有），
 * 所以这一版退到「发货后 15 天」—— 同样是通行档位，且不依赖还不存在的数据。
 * 配置项留着，接上签收后把它调小即可。
 *
 * <p><b>一天一轮就够</b>：判据的粒度是「天」，跑得再密也不会让任何一单提前完成，
 * 只是白扫。放在凌晨 3 点 —— 那时下单量最低，而它扫的是 FULFILLING 全表。
 */
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
@Component
public class OrderAutoReceiptJob implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderAutoReceiptJob.class);

    private final OrderService orderService;
    private final JobSupport jobs;

    /**
     * 发货后多少天自动确认收货。
     *
     * <p><b>0 或负数 = 关掉这个任务</b>（{@code autoConfirmReceipt} 直接返回 0）——
     * 出了争议要紧急停掉时，改配置比改代码快。
     */
    private final int shippedDays;

    /**
     * 签收后多少天自动确认收货。行业惯例 7 天（淘宝/拼多多）。
     * <b>0 = 关掉签收判据</b>，整条退回只按发货算。
     */
    private final int signedDays;

    /**
     * <b>构造注入，不是 {@code @Value} 字段注入。</b>
     *
     * <p>字段注入会让这个任务在测试里静默失效：Job 是 {@code @Profile("worker")} 的，
     * 测试拿不到容器里的 Bean，只能手工 new —— 那时 {@code @Value} 不生效，
     * {@code shippedDays} 是 0，而 0 在 {@code autoConfirmReceipt} 里等于「关掉」。
     * 于是测试跑完一单都没确认，**而断言「没有误确认」的那几条全都绿**。
     * 构造注入把这个参数摆到调用点上，测试必须显式给一个值。
     */
    public OrderAutoReceiptJob(OrderService orderService, JobSupport jobs,
                               @Value("${shop.job.order-auto-receipt.shipped-days:15}") int shippedDays,
                               @Value("${shop.job.order-auto-receipt.signed-days:7}") int signedDays) {
        this.orderService = orderService;
        this.jobs = jobs;
        this.shippedDays = shippedDays;
        this.signedDays = signedDays;
    }

    @Scheduled(cron = "${shop.job.order-auto-receipt.cron:0 0 3 * * *}")
    // 自动确认收货是**不可逆的**：确认完就进结算，钱开始往商家走。
    // 方法内部幂等（只动 FULFILLING 的行），但两个实例同时扫会白跑一遍并抢行锁。
    // lockAtMostFor 给 10 分钟：它扫的是 FULFILLING 全表，比关单那个大
    @SchedulerLock(name = "order-auto-receipt", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    public void confirm() {
        jobs.run("order-auto-receipt", () -> run(null).detail());
    }

    @Override
    public String name() {
        return "order-auto-receipt";
    }

    @Bean
    public JobDeclaration orderautoreceiptDeclaration() {
        return new JobDeclaration("order-auto-receipt", "发货超时自动确认收货",
                "把发货满 N 天的配送单置为已完成，让货款进入结算。"
                        + "不跑的话买家不点确认，商家的钱就永远提不出来，且没有任何报错",
                "shop-core", "0 0 3 * * *", true,
                // 一天一轮：超时 9 分钟（< 10 分钟的锁），锁 600 秒是崩溃恢复的上限
                540, 600,
                true,
                // **每天一轮，落全量日志**：一年 365 行，而每一行都对应一批
                // 「钱开始往商家走」的单子。出诉时要查得到那天确认了哪些
                true);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        int n = orderService.autoConfirmReceipt(System.currentTimeMillis(), shippedDays, signedDays);
        if (n == 0) {
            return JobResult.ok(null);
        }
        // info 而不是 debug：这是**用户看得见的**结果（他的订单变成已完成、钱开始结算）
        log.info("[order] 发货满 {} 天自动确认收货 {} 笔，货款进入结算", shippedDays, n);
        return JobResult.ok(n + " 笔发货超时订单已自动确认收货");
    }
}

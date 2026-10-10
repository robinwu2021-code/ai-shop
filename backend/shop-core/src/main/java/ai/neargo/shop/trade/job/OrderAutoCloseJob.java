package ai.neargo.shop.trade.job;

import org.springframework.context.annotation.Bean;
import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.trade.service.OrderService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 关掉超时未支付的订单，并释放它们占住的库存、券、积分。
 *
 * <p><b>这个任务此前不存在，而 {@code closeExpiredOrders} 早就写好了</b> ——
 * 全仓生产代码对它零引用，只有 {@code M3TradeFlowTest} 在调。
 * <b>与本轮撞到的 Outbox 是一模一样的形状</b>：能力写完了、测试自己把它调起来，
 * 于是缺的那个调度器在测试里完全不可见。
 *
 * <p>没有它的后果是**静默的、持续累积的**：
 * <pre>
 *   待支付单永不关闭  →  ord_order 里 WAIT_PAY 只增不减
 *   库存永不释放      →  锁定量单调增长，最后商品显示无货而实际有货
 *   券与积分永不退回  →  用户的券卡在「已使用」，他没买到东西也用不了
 * </pre>
 * 全程不报错。运营看到的是「这个商品怎么老是缺货」，
 * 而没有任何东西指向「关单任务从来没被写出来」。
 *
 * <p><b>一分钟一轮</b>：关单晚一分钟，库存就多占一分钟；而扫描本身按
 * {@code pay_deadline_at} 走索引，一轮的代价与待支付单的数量成正比，
 * 在任何正常规模下都是毫秒级。
 */
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
@Component
public class OrderAutoCloseJob implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderAutoCloseJob.class);

    private final OrderService orderService;
    private final JobSupport jobs;

    public OrderAutoCloseJob(OrderService orderService, JobSupport jobs) {
        this.orderService = orderService;
        this.jobs = jobs;
    }

    @Scheduled(cron = "${shop.job.order-auto-close.cron:0 * * * * *}")
    // **关单是不可逆的**（状态改了、库存放了、券退了），所以重复执行的代价高于一般任务。
    // closeOne 内部是幂等的（只动 WAIT_PAY 的行、release 只认 LOCKED 的锁定行），
    // 但两个实例同时扫同一批仍会白跑一遍并抢行锁 —— 而这条跑在下单的同一批行上。
    // lockAtMostFor 给 3 分钟（大于一轮该花的时间，小于「卡住了还占着」的忍耐度）
    @SchedulerLock(name = "order-auto-close", lockAtLeastFor = "PT30S", lockAtMostFor = "PT3M")
    public void close() {
        // 触发器只负责「到点了」；任务体在 run() 里。J1 只搬不改
        jobs.run("order-auto-close", () -> run(null).detail());
    }

    @Override
    public String name() {
        return "order-auto-close";
    }

    /** 声明。displayName 是运营页面直接显示的那句话 —— 不能是锁名。 */
    @Bean
    public JobDeclaration orderautocloseDeclaration() {
        return new JobDeclaration("order-auto-close", "订单超时自动关单",
                "关掉超时未支付的订单并释放库存、券与积分。不跑的话库存被永久占住，那些货再也卖不出去",
                "shop-core", "0 * * * * *", true,
                // 超时 50 秒（< 60 秒的间隔）：跑过一分钟就该被下一轮的锁拦住，
                // 而不是两轮叠在一起。锁 180 秒是崩溃恢复的上限，不是预期耗时
                50, 180,
                true,
                // **每分钟的任务不落全量日志**：一天 1440 行，30 天保留下 4.3 万行，
                // 而其中绝大多数是「又成功了」。稀疏日志只在状态变化或失败时写一行，
                // 于是日志读起来是状态变迁史，不是流水账
                false);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        int n = orderService.closeExpiredOrders(System.currentTimeMillis());
        if (n == 0) {
            // **detail 保持 null** —— JobSupport 用它区分「跑了但没事」，J1 连它都不能变
            return JobResult.ok(null);
        }
        // info 而不是 debug：关单是**用户看得见的**结果（他的订单没了），
        // 出诉时要查得到那个时段关了多少
        log.info("[order] 超时未支付自动关单 {} 笔，库存/券/积分已释放", n);
        return JobResult.ok(n + " 笔超时未支付订单已关闭");
    }
}

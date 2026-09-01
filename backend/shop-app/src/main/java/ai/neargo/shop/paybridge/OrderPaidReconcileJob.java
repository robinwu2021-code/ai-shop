package ai.neargo.shop.paybridge;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 不变式 I8 的调度壳 —— 判断与补偿都在 {@link OrderPaidReconciler}。
 *
 * <p>它守的是<b>「用户付了钱，订单还显示待支付」</b>。
 * 详见 {@code OrderPaidReconciler} 的类注释。
 */
@Component
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
public class OrderPaidReconcileJob implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderPaidReconcileJob.class);

    /**
     * 回看窗口。给 26 小时而不是 1 小时，理由与 {@code fund-invariant} 那条一样：
     * 任务本身可能停摆几轮（发版、锁没释放），窗口只留一小时的话，
     * <b>停摆期间付款的那些单再也不会被扫到</b>。
     */
    @Value("${shop.job.order-paid-recon.lookback-hours:26}")
    private int lookbackHours;

    @Value("${shop.job.order-paid-recon.limit:2000}")
    private int limit;

    private final OrderPaidReconciler reconciler;
    private final JobSupport jobs;

    public OrderPaidReconcileJob(OrderPaidReconciler reconciler, JobSupport jobs) {
        this.reconciler = reconciler;
        this.jobs = jobs;
    }

    /*
     * 与 fund-invariant 错开 20 分钟：两个任务都要扫支付流水，
     * 撞在同一分钟上的话数据库的这一下会比平时重一倍，而它们之间没有先后依赖。
     */
    @Scheduled(cron = "${shop.job.order-paid-recon.cron:0 40 * * * *}")
    @SchedulerLock(name = "order-paid-recon", lockAtLeastFor = "PT1M", lockAtMostFor = "PT50M")
    public void scan() {
        jobs.run("order-paid-recon", () -> run(null).detail());
    }

    @Override
    public String name() {
        return "order-paid-recon";
    }

    @Bean
    public JobDeclaration orderPaidReconDeclaration() {
        return new JobDeclaration("order-paid-recon", "支付成功但订单未转已支付",
                "拉一批支付域里成功的付款流水，比对订单是不是已支付；不是的自动补一次 markPaid（幂等）。"
                        + "补不动的（比如订单已取消）单独报出来 —— 那种要人来决定退款还是恢复",
                "shop-app", "0 40 * * * *", true,
                2700, 3000, true, true);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        long since = System.currentTimeMillis() - lookbackHours * 3_600_000L;
        OrderPaidReconciler.Result r = reconciler.reconcile(since, limit);

        /*
         * **对照量先判。**「没有不一致」与「一笔都没扫到」在结果上长得一样，
         * 而后者才是该红的那种：查询条件写错、时间窗算反、支付域压根没在写流水。
         * 不单独报的话，运营页面上是一行绿色的「成功」，
         * 而它代表的是「看过的那些没问题」—— 看过的是零个。
         */
        if (r.scanned() == 0) {
            log.warn("[order-paid-recon] **一笔成功支付都没扫到**（回看 {} 小时）—— "
                    + "正常情况下这个数不该是 0，先确认支付域在不在写流水", lookbackHours);
            return JobResult.ok("扫描 0 笔（回看 %d 小时）—— 这个数不该是 0".formatted(lookbackHours));
        }

        String detail = "扫描 %d 笔成功支付｜补了 %d 个订单｜补不动 %d 个"
                .formatted(r.scanned(), r.repaired(), r.failed());

        /*
         * 补不动的算任务失败，不是「顺带一提」。
         * 那一类是「支付域收到了钱、而订单已经取消/关闭」—— 用户的钱在平台手里，
         * 而单子没了。它需要人来决定退款还是恢复，报成绿色就没人会来。
         */
        if (r.failed() > 0) {
            log.error("[order-paid-recon] {} —— 补不动的要人工看", detail);
            return JobResult.failed(detail, "补不动的要人工看：多半是订单已取消而钱已收到");
        }
        if (r.repaired() > 0) {
            log.warn("[order-paid-recon] {}", detail);
        }
        return JobResult.ok(detail);
    }
}

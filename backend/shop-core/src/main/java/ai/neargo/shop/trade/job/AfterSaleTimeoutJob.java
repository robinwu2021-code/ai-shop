package ai.neargo.shop.trade.job;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.trade.service.AfterSaleRuleService;
import ai.neargo.shop.trade.service.AfterSaleService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 售后时效（TDD-C 端商品详情页·内容丰富度 §3）：三条时限到了就替沉默的一方做决定。
 *
 * <pre>
 *   商家 replyHours 不处理        → 系统自动同意（仅退款直接退；退货退款转「等寄回」）
 *   买家 shipBackDays 不寄回      → 关闭本次申请（可重新申请）
 *   商家 confirmHours 不确认收货  → 系统退款
 * </pre>
 *
 * <p><b>这三条此前一条都没有。</b> 全仓只有「关未付款单」与「退款重试」两个定时任务，
 * 没有任何东西扫 {@code APPLIED} —— 商家不点，售后单永久挂着，而详情页上写着「极速退款」。
 * 超出极速退阈值的单就落在这个没有尽头的等待里，全程零报错：用户看到的是「等商家处理」，
 * 而没有任何东西指向「根本没有人在计时」。
 *
 * <p><b>平台介入（T4）不在这里。</b> {@code interveneWorkDays} 只用于展示与超期告警 ——
 * 钱的判定不能由定时任务做：那等于让平台在没看材料的情况下替一方认赔。
 *
 * <p><b>十分钟一轮</b>：时限的量级是小时与天，分钟级的精度毫无意义，而每一轮都要扫三次表。
 */
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
@Component
public class AfterSaleTimeoutJob implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(AfterSaleTimeoutJob.class);

    /**
     * 单轮单类上限。**超出的留到下一轮** —— 自动同意会真的把钱退出去，
     * 一次退几千笔会把支付通道打满，而这批单已经等了两天，再等十分钟没有区别。
     */
    private static final int BATCH = 100;

    private final AfterSaleService afterSaleService;
    private final AfterSaleRuleService ruleService;
    private final JobSupport jobs;

    public AfterSaleTimeoutJob(AfterSaleService afterSaleService,
                               AfterSaleRuleService ruleService, JobSupport jobs) {
        this.afterSaleService = afterSaleService;
        this.ruleService = ruleService;
        this.jobs = jobs;
    }

    @Scheduled(cron = "${shop.job.after-sale-timeout.cron:0 */10 * * * *}")
    // 自动同意是**不可逆的**（钱退出去了），所以重复执行的代价高于一般任务。
    // 三个 auto* 方法各自幂等（都会重查状态，状态不对就直接返回），但两个实例同时扫
    // 同一批仍会白跑一遍并抢同一批行锁 —— 而这批行正是商家此刻可能在点「同意」的那些
    @SchedulerLock(name = "after-sale-timeout", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    public void sweep() {
        jobs.run("after-sale-timeout", () -> run(null).detail());
    }

    @Override
    public String name() {
        return "after-sale-timeout";
    }

    /** 声明。displayName 是运营页面直接显示的那句话 —— 不能是锁名。 */
    @Bean
    public JobDeclaration aftersaletimeoutDeclaration() {
        return new JobDeclaration("after-sale-timeout", "售后超时自动处理",
                "商家 48 小时不处理就替他同意、买家 7 天不寄回就关闭申请、商家 48 小时不确认收货就退款。"
                        + "不跑的话售后单会永久挂着，而详情页上的「极速退款」是对外承诺",
                "shop-core", "0 */10 * * * *", true,
                // 超时 9 分钟（< 10 分钟的间隔），锁 10 分钟是崩溃恢复的上限，不是预期耗时
                540, 600,
                // 允许手工触发：上线当天要能立刻跑一轮看结果，不必等下一个十分钟
                true,
                // **落全量日志**：与每分钟跑的关单任务不同，这条十分钟一轮（一天 144 行），
                // 而它每一次动作都是「平台替人做了决定并动了钱」—— 那是要能逐条追的
                true);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        var rule = ruleService.get();
        long now = System.currentTimeMillis();

        int approved = each(afterSaleService.idlePendingNos(
                        now - rule.replyHours() * 3_600_000L, BATCH),
                afterSaleService::autoApprove, "自动同意");
        int closed = each(afterSaleService.unshippedReturnNos(
                        now - rule.shipBackDays() * 86_400_000L, BATCH),
                afterSaleService::autoCloseUnshipped, "逾期未寄回关闭");
        int refunded = each(afterSaleService.unconfirmedReturnNos(
                        now - rule.confirmHours() * 3_600_000L, BATCH),
                afterSaleService::autoConfirmReturn, "逾期未确认收货退款");

        if (approved == 0 && closed == 0 && refunded == 0) {
            // **detail 保持 null** —— JobSupport 用它区分「跑了但没事」
            return JobResult.ok(null);
        }
        // info 而不是 debug：这三件都是用户与商家看得见的结果，出诉时要查得到那个时段做了多少
        log.info("[after-sale] 超时处置：自动同意 {} 笔、逾期未寄回关闭 {} 笔、逾期未确认退款 {} 笔",
                approved, closed, refunded);
        return JobResult.ok("自动同意 %d 笔、关闭 %d 笔、自动退款 %d 笔".formatted(approved, closed, refunded));
    }

    /**
     * 逐条处置，<b>一条失败不拖累其余</b>。
     *
     * <p>失败最常见的原因是分账已过期（{@code doRefund} 的第一步就停下），那一笔会留在原状态，
     * 下一轮再来 —— 而如果这里不接住异常，它会让后面几十笔当轮一条都跑不了，
     * 且 job 的结果只显示一个异常，看不出真正处置了几笔。
     */
    private int each(List<String> nos, java.util.function.Consumer<String> action, String what) {
        int n = 0;
        for (String no : nos) {
            try {
                action.accept(no);
                n++;
            } catch (RuntimeException e) {
                log.warn("[after-sale] {} 失败 {}：{}", what, no, e.getMessage());
            }
        }
        return n;
    }
}

package ai.neargo.shop.merchant.job;

import org.springframework.context.annotation.Bean;
import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.merchant.service.MerchantPlanService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 增值包到期扫描：ACTIVE → GRACE（宽限 7 天）→ EXPIRED（降级压店）。
 *
 * <p><b>为什么必须有这个 job，而不是「读的时候顺手判一下」</b>：过期的后果是
 * <b>状态变更</b>（压店只读、停子账号），不是一次查询结果的差异。惰性判定只在有人来读时才发生 ——
 * 而一家商家过期后可能几天都没人访问他的后台，那几天里他的门店照常接单，
 * 事后再压等于把「已经卖掉的货」变成争议。
 *
 * <p><b>只在 worker 部署跑</b>：与 {@link QualificationExpiryJob} 同理，
 * 批量任务不该和下单接口抢连接池。
 *
 * @see MerchantPlanService#sweepExpiry(long) 幂等口径与降级选店规则都在那里
 */
@Profile("worker")
@Component
public class PlanExpiryJob implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(PlanExpiryJob.class);

    private final MerchantPlanService planService;
    private final AuditLogPort auditLogPort;
    private final JobSupport jobs;

    public PlanExpiryJob(MerchantPlanService planService, AuditLogPort auditLogPort,
                         JobSupport jobs) {
        this.planService = planService;
        this.auditLogPort = auditLogPort;
        this.jobs = jobs;
    }

    /**
     * 每天凌晨扫一次。
     *
     * <p>频率不必更高：到期是以天为单位的事，且 <b>GRACE 期本身就是缓冲</b> ——
     * 扫描晚跑几个小时，落进宽限期的人一个能力都不少。
     *
     * <p>刻意排在资质扫描（03:10）<b>之后</b>：两者都会改门店状态，
     * 同时跑的话审计里两条记录的先后顺序每天不一样，排查「这店是被谁压的」会多绕一圈。
     */
    @Scheduled(cron = "${shop.job.plan-expiry.cron:0 25 3 * * *}")
    // 重复执行幂等（靠 downgraded_at 判「已降过」），但会重复写审计 ——
    // 商家看到两条「已降级」会以为被降了两次。
    @SchedulerLock(name = "plan-expiry", lockAtLeastFor = "PT4M", lockAtMostFor = "PT30M")
    public void scan() {
        // 触发器只负责「到点了」；任务体在 run() 里。J1 只搬不改
        jobs.run("plan-expiry", () -> run(null).detail());
    }

    @Override
    public String name() {
        return "plan-expiry";
    }

    /** 声明。displayName 是运营页面直接显示的那句话 —— 不能是锁名。 */
    @Bean
    public JobDeclaration planexpiryDeclaration() {
        return JobDeclaration.daily("plan-expiry", "增值包到期扫描",
                "扫出到期的商家增值包：进宽限期或降级，并把超额门店压成只读。不跑的话过期商家照常接单",
                "shop-merchant", "0 25 3 * * *");
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        MerchantPlanService.SweepResult r = planService.sweepExpiry(System.currentTimeMillis());
        if (r.toGrace() == 0 && r.toExpired() == 0) {
            return JobResult.ok(null);
        }
        log.warn("增值包到期扫描：{} 家进入宽限期，{} 家已降级，压为只读 {} 家门店",
                r.toGrace(), r.toExpired(), r.storesSuspended());
        // 写成一条汇总而不是逐商家一条：这个 job 的读者是运营，他要看的是
        // 「今天有多少人掉下去了」；逐户明细在到期看板上按条件筛，那里才是干活的地方。
        auditLogPort.record("PLAN_EXPIRY_SWEEP", "-",
                r.toGrace() + " 家进入宽限期；" + r.toExpired() + " 家降级，压为只读 "
                        + r.storesSuspended() + " 家门店",
                r.toExpired() > 0);
        return JobResult.ok("宽限 " + r.toGrace() + " / 降级 " + r.toExpired());
    }
}

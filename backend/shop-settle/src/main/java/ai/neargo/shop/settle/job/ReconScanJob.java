package ai.neargo.shop.settle.job;

import org.springframework.context.annotation.Bean;
import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.settle.service.ReconService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 对账自查：每 10 分钟扫一轮超时未终态的收款。
 *
 * <p><b>为什么这么频繁</b>：它止的是掉单的血 —— 用户付了钱而我方没收到回调，
 * 每多等一分钟，他就多看一分钟「我的订单不见了」。一天一次的话，
 * 用户早就先来投诉了，而这件事本可以自动修好。
 *
 * <p><b>只在 worker 部署跑</b>（与资质扫描同一条规矩）：多实例各跑一遍时，
 * 同一笔单会被查两次 —— 查单本身无害，但补回支付会并发走两遍成功链路，
 * 幂等挡得住重复入账，挡不住两条重复的通知。
 */
@Profile("worker")
@Component
public class ReconScanJob implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(ReconScanJob.class);

    private final ReconService reconService;
    private final JobSupport jobs;

    public ReconScanJob(ReconService reconService, JobSupport jobs) {
        this.reconService = reconService;
        this.jobs = jobs;
    }

    @Scheduled(cron = "${shop.job.recon-scan.cron:0 */10 * * * *}")
    // **四个任务里重叠窗口最大的一个** —— 10 分钟一次，一次跑久了就会和下一次撞上。
    // lockAtMostFor 给 9 分钟（小于间隔）：真卡死时下一轮能接手，不至于整条对账停摆。
    // lockAtLeastFor 只给 30 秒 —— 扫得快是常态，锁太久会让下一轮白等。
    @SchedulerLock(name = "recon-scan", lockAtLeastFor = "PT30S", lockAtMostFor = "PT9M")
    public void scan() {
        // 触发器只负责「到点了」；任务体在 run() 里。
        // **J1 这一批只搬不改** —— @Scheduled 与 @SchedulerLock 暂时保留，J2 装上注册表后才摘
        jobs.run("recon-scan", () -> run(null).detail());
    }

    @Override
    public String name() {
        return "recon-scan";
    }

    /** 声明。displayName 是**运营页面直接显示的那句话** —— 不能是锁名，运营看不懂。 */
    @Bean
    public JobDeclaration reconscanDeclaration() {
        return new JobDeclaration("recon-scan", "对账自查",
                "扫出平台账与渠道账对不上的流水：补回漏记的、关掉该关的，其余留待下轮",
                "shop-settle", "0 */10 * * * *", true, 60, 540, true, true);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        ReconService.ScanResult r = reconService.scan(System.currentTimeMillis());
        if (r.scanned() == 0) {
            // **detail 保持 null**，不要改成「无可处理项」之类的话。
            // JobSupport 用它区分「跑了但没事」与「跑了并做了事」，
            // 而 J1 这一批的全部价值是行为等价 —— 连写进 sys_job_run.detail 的内容都不能变
            return JobResult.ok(null);
        }
        /*
         * 有补回或关单就打 WARN：这两件事都意味着回调链路漏了一笔，
         * 而回调持续漏单是要人去查的（通道配置、回调域名、我方 502），
         * 不该淹没在 INFO 里。
         */
        if (r.repaired() > 0 || r.closed() > 0) {
            log.warn("[recon] 自查 {} 笔：**补回 {}** · 关单 {} · 留待下轮 {} —— "
                            + "补回不为零说明支付回调漏了单，要查回调链路",
                    r.scanned(), r.repaired(), r.closed(), r.deferred());
        }
        return JobResult.ok("自查 %d 笔（补回 %d · 关单 %d · 留待 %d）"
                .formatted(r.scanned(), r.repaired(), r.closed(), r.deferred()));
    }
}

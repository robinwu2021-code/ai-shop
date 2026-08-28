package ai.neargo.shop.settle.job;

import java.util.List;
import org.springframework.context.annotation.Bean;
import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.settle.PointsService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 待生效积分转正：把过了售后期的分从 {@code pending_balance} 挪进 {@code balance}。
 *
 * <p><b>这个任务此前不存在，而整条积分链依赖它。</b>
 * {@code PointsServiceImpl.grantOnPay} 的注释里写着「转正由独立任务负责」——
 * 那句话是对的，只是那个任务从来没被写出来。
 *
 * <p>后果链，全程不报错：
 * <pre>
 *   发放时不写 available_at  →  没有任何任务扫得到
 *   grantPending 只加 pending →  balance 恒 0
 *   maxUsablePoints 算出 0    →  抵扣永远抵不了
 * </pre>
 * 用户看得见分在涨，却一分也花不出去；页面连「何时生效」都显示不了
 * （{@code nextActivate} 因 available_at 为 NULL 恒返回 null）。
 *
 * <p><b>频率：每天一次就够。</b>售后期以天为单位，早几小时晚几小时不影响任何判断，
 * 而更高的频率只会让这条「每行都要改账户余额」的链路更频繁地和下单抢锁。
 *
 * <p><b>多实例：已由 ShedLock 接管</b>（2026-08-13）。此前只靠 {@code activated_at} 幂等 +
 * {@code pending_balance >= points} 的乐观守卫兜底 —— 那能保证不重复加钱，
 * <b>但保证不了不空转</b>：两个实例同时扫同一批行，一个成功一个全程回滚，
 * 日志里看到的是「转正 0 条」，和「今天确实没有到点的」长得一模一样。
 * 幂等守的是正确性，锁守的是可观测性。
 */
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
@Component
public class PointsActivateJob {

    private static final Logger log = LoggerFactory.getLogger(PointsActivateJob.class);

    private final PointsService pointsService;
    private final JobSupport jobs;

    public PointsActivateJob(PointsService pointsService, JobSupport jobs) {
        this.pointsService = pointsService;
        this.jobs = jobs;
    }

    /**
     * 每天 00:05 扫一次。
     *
     * <p>放在零点之后几分钟，而不是零点整：那个时刻挤着一批日结任务，
     * 而这条要改账户余额，和下单走同一批行。
     */
    @Scheduled(cron = "${shop.job.points-activate.cron:0 5 0 * * *}")
    // lockAtLeastFor 比 cron 间隔短得多即可，这里给 4 分钟：防的是「实例 A 秒级跑完释放锁、
    // 实例 B 的定时器晚几秒触发又拿到锁」。日结任务不怕多锁几分钟，怕的是白跑一遍。
    @SchedulerLock(name = "points-activate", lockAtLeastFor = "PT4M", lockAtMostFor = "PT30M")
    public void activate() {
        // 触发器只负责「到点了」；任务体在 activateHandler 里。J1 只搬不改
        jobs.run("points-activate", () -> activateHandler().run(null).detail());
    }

    /**
     * 到期清零：把闲置满 {@code inactiveDays} 的账户余额清空，并转为平台收入。
     *
     * <p><b>它是恒等式成立的前提</b>：不清零的话，用户的分过期了流通侧不减、
     * 池子侧也不记 {@code EXPIRE_INCOME} —— 池子只增不减，
     * <b>失衡量随时间单调增长</b>。
     *
     * <p>放在转正之后（00:20）而不是同一时刻：两者都要改账户余额，
     * 错开可以让「哪一步出的问题」在日志里一眼分得出来。
     */
    @Scheduled(cron = "${shop.job.points-expire.cron:0 35 0 * * *}")
    // **锁名与 activate 分开**：两者错开 15 分钟，共用一把锁的话，
    // activate 的 lockAtLeastFor 还没释放就轮到 expire，会被静默跳过一整天
    @SchedulerLock(name = "points-expire", lockAtLeastFor = "PT4M", lockAtMostFor = "PT30M")
    public void expire() {
        jobs.run("points-expire", () -> expireHandler().run(null).detail());
    }

    /**
     * <b>这个类承载两个任务</b>（转正、到期清零），所以它自己不 implements JobHandler ——
     * 任务的身份是**锁名**，一个类可以有多个。
     *
     * <p>盘点时按类数会把这两个数成一个，而它们在 {@code sys_job_run} 里是两行。
     */
    @Bean
    public JobHandler pointsActivateHandler() {
        return activateHandler();
    }

    @Bean
    public JobHandler pointsExpireHandler() {
        return expireHandler();
    }

    private JobHandler activateHandler() {
        return new JobHandler() {
            @Override
            public String name() {
                return "points-activate";
            }

            @Override
            public JobResult run(JobInvocation invocation) {
                int n = pointsService.activateDuePoints();
                if (n == 0) {
                    // 「扫过了，没有到点的」是正常的，不值得每天打一行 warn。
                    // 但也不能完全不记 —— 任务没跑与跑了没事，排查时是两件事，
                    // 而这正是 JobSupport 的运行记录负责区分的
                    log.debug("积分转正：本次没有到点的待生效分");
                    // **detail 保持 null** —— JobSupport 用它区分「跑了但没事」，J1 连它都不能变
                    return JobResult.ok(null);
                }
                log.info("积分转正：{} 条待生效分已转为可用", n);
                return JobResult.ok(n + " 条待生效分已转为可用");
            }
        };
    }

    private JobHandler expireHandler() {
        return new JobHandler() {
            @Override
            public String name() {
                return "points-expire";
            }

            @Override
            public JobResult run(JobInvocation invocation) {
                int n = pointsService.expireIdleAccounts();
                if (n == 0) {
                    log.debug("积分到期：本次没有到期账户");
                    return JobResult.ok(null);
                }
                // 清零是**用户看得见的损失**，用 info 不用 debug —— 出诉时要查得到那天清了多少
                log.info("积分到期清零：{} 个账户余额已清空并转入平台收入", n);
                return JobResult.ok(n + " 个账户余额已清空并转入平台收入");
            }
        };
    }

    /*
     * 两条声明**各自一个 bean**，不能合成一个 List<JobDeclaration> 的 bean ——
     * Spring 注入 List<JobDeclaration> 时收集的是「类型为 JobDeclaration 的 bean」，
     * 不会把一个本身是 List 的 bean 摊开。合着写编译过、启动也过，
     * 只是那两个任务**永远不会出现在注册表里**，而页面上什么都看不到。
     */
    /**
     * <h2>积分三连的先后是有依赖的，而调度器不知道</h2>
     * <p>转正 → 清零 → 恒等式自检，三个任务必须按这个顺序、且互不重叠：
     * 自检核的是「池子里的钱 = 流通的分 + 未兑付的分」，
     * 而前两个任务正在改的就是等式两边。<b>在它们跑到一半时核对，会算出一个
     * 不存在的差额</b> —— 而自检把失衡打成 error 并写着「为负是真实资金缺口」。
     *
     * <p><b>假警报的代价比漏报还大</b>：它出现几次之后，真的资金缺口来临时
     * 没有人会再当回事。
     *
     * <p>现在靠的<b>只是时间间隔</b>（00:05 / 00:35 / 01:20，各留 30 与 45 分钟）。
     * 原先是 00:05 / 00:20 / 00:40 —— 转正跑过 15 分钟，清零就压上来了。
     * 间隔拉开是缓解不是修复：<b>正确的修法是调度器支持依赖（B 等 A 跑完）
     * 或互斥组（三者共用一把锁）</b>，两者都是设计决策，不该在这里顺手加。
     * 在那之前，谁要改这三个的 cron，先读完这段。
     */
    @Bean
    public JobDeclaration pointsActivateDeclaration() {
        return JobDeclaration.daily("points-activate", "待生效积分转正",
                "把过了售后期的待生效积分转成可用余额。不跑的话用户的分永远停在「待生效」。"
                        + "⚠ 必须跑在「闲置积分清零」之前，且不能与它重叠",
                "shop-settle", "0 5 0 * * *");
    }

    @Bean
    public JobDeclaration pointsExpireDeclaration() {
        return JobDeclaration.daily("points-expire", "闲置积分清零",
                "把闲置满期的账户余额清空并计入平台收入。不跑的话积分池只增不减，对不平。"
                        + "⚠ 要在「待生效积分转正」跑完之后、「恒等式自检」之前",
                "shop-settle", "0 35 0 * * *");
    }
}

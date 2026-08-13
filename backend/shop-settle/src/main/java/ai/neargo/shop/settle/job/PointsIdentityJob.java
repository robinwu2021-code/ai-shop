package ai.neargo.shop.settle.job;

import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.settle.PointsService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 积分恒等式每日自检：<b>池子里的钱 == 还欠着用户的钱</b>。
 *
 * <p>积分域-完整方案写着「对账任务每日校验。恒等式是这套设计<b>唯一的自检手段</b>，
 * 违反即告警」—— <b>而这个任务此前不存在</b>。两边的数只在 ops 看板上并排显示，
 * 没有任何人比较它们，更没有人会在半夜发现它们分家了。
 *
 * <p><b>为什么现在才做得成</b>：在入账（发分收费）与出账（兑付、到期）
 * 两侧接上之前，等式两边恒等于 0 —— 校验了也看不出任何问题。
 * 那正是这套账最危险的一段时间：<b>看着挺平，因为根本没有数</b>。
 *
 * <p><b>失衡是单调增长的</b>，这是它必须每天查的理由。
 * 本轮修的两个缺口（到期不入账、发分不收费）都属于这一类：
 * 每天差一点，半年之后差额已经大到查不回是从哪天开始的。
 * 一天一次足够 —— 差额不会在几小时内变得不可收拾，
 * 而更高的频率只是让同一条告警重复响。
 */
@Profile("worker")
@Component
public class PointsIdentityJob {

    private static final Logger log = LoggerFactory.getLogger(PointsIdentityJob.class);

    /**
     * 要查的市场。
     *
     * <p><b>逐个市场分别查，不合成一个总数</b>：账面上一个总数是平的，
     * 而两个市场可能一个溢一个空 —— 合起来看正好抵消，
     * 那是最难发现的一种失衡。同理见 {@code overview} 里按通道分账本。
     */
    private static final List<String> MARKETS = List.of("CN");

    private final PointsService pointsService;
    private final JobSupport jobs;

    public PointsIdentityJob(PointsService pointsService, JobSupport jobs) {
        this.pointsService = pointsService;
        this.jobs = jobs;
    }

    /**
     * 每天 00:40。
     *
     * <p>排在转正（00:05）与到期清零（00:20）<b>之后</b>：
     * 那两个任务都会同时改动等式的两边，查在中间会读到一个正在变化的快照，
     * 报出来的失衡是假的 —— <b>而假告警比没有告警更糟</b>，
     * 它会让真的那次被当成又一次误报。
     */
    @Scheduled(cron = "${shop.job.points-identity.cron:0 40 0 * * *}")
    @SchedulerLock(name = "points-identity", lockAtLeastFor = "PT4M", lockAtMostFor = "PT30M")
    public void check() {
        jobs.run("points-identity", () -> {
            StringBuilder summary = new StringBuilder();
            int broken = 0;
            for (String market : MARKETS) {
                PointsService.IdentityCheck c = pointsService.checkIdentity(market);
                if (c.balanced()) {
                    summary.append("[%s 平]".formatted(market));
                    continue;
                }
                broken++;
                /*
                 * **打 error 而不是 warn**：这条不是「要留意一下」，
                 * 是「这套账已经不成立了」。差额为正说明池子里有没人认领的钱，
                 * 为负说明平台欠的比池子里的多 —— 后者是真实的资金缺口。
                 *
                 * 把两边的数与 PENDING 一起打出来，是因为排查的第一步永远是
                 * 「差在哪一侧」：不打的话看到的只是一个孤零零的差额。
                 */
                log.error("[points] ★ 恒等式失衡 market={} 差额={}分（池子={} 应欠={}"
                                + " ← 流通{}分 + 未兑付{}分）—— "
                                + "差额为负是真实资金缺口；为正是池子里有没人认领的钱。"
                                + "先查当天的 EXPIRE_INCOME 与 MERCHANT_RECEIVE 有没有漏记",
                        c.market(), c.diffMinor(), c.poolBalanceMinor(), c.owedMinor(),
                        c.circulatingPoints(), c.pendingUseMinor());
                summary.append("[%s 失衡%d分]".formatted(market, c.diffMinor()));
            }
            // 平的时候也返回一句：JobSupport 的运行记录里要看得出「今天查过了」——
            // 返回 null 的话，「查了都平」与「任务没跑」在记录上长得一模一样，
            // 而这正是本轮反复踩到的那个形状
            return "恒等式自检 %d 个市场，失衡 %d 个 %s".formatted(MARKETS.size(), broken, summary);
        });
    }
}

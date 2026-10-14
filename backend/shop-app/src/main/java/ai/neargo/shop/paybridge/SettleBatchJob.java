package ai.neargo.shop.paybridge;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.pay.SettleBatchService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 账期推进：每小时把结算单往放款推一步（TDD-账期推进与放款记录 批 1）。
 *
 * <p><b>这是「那个推动者」的后半段。</b>{@code SettleBatchService} 的类注释写着
 * 「结算单生成之后没有任何东西推动它」—— 2026-10-09 之前，三步推进方法在主代码里零调用，
 * 而 {@code SettleBatchFlowTest} 14 条全绿。覆盖得越漂亮，越没人回头问「它到底跑没跑」。
 *
 * <h2>四步顺序不能换</h2>
 * 定 T2 → 入批 → 截批 → 自查。换序会让当轮新定 T2 的单赶不上这一轮入批，推迟一小时 ——
 * 不报错，只是钱晚到。
 *
 * <h2>两道上线闸（§2.1 C）</h2>
 * <ul>
 *   <li><b>dry-run 默认开</b>：只报「这一轮会动多少」，不写库。第一轮上线前运营看过数再翻 false。</li>
 *   <li><b>起始日</b>：只推进成交时刻 ≥ 起始日的单。存量继续走逐张 confirm / paid 的老路，
 *       两条路并存到存量清零 —— 不限的话第一轮会把几个月的存量一次性卷进批次，
 *       而其中一部分财务已经按老路付过了。</li>
 * </ul>
 *
 * <p>只在 worker 部署跑（与 recon-scan 同一条规矩）。
 */
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
@Component
public class SettleBatchJob implements JobHandler {

    public static final String NAME = "settle-batch";
    private static final Logger log = LoggerFactory.getLogger(SettleBatchJob.class);

    private final SettleBatchService batches;
    private final JobSupport jobs;
    private final boolean dryRun;
    private final Long startAt;

    public SettleBatchJob(SettleBatchService batches, JobSupport jobs,
                          @Value("${shop.job.settle-batch.dry-run:true}") boolean dryRun,
                          @Value("${shop.job.settle-batch.start-date:}") String startDate,
                          @Value("${shop.settle.zone:Asia/Shanghai}") String zone) {
        this.batches = batches;
        this.jobs = jobs;
        this.dryRun = dryRun;
        this.startAt = parseStart(startDate, zone);
    }

    /**
     * 起始日 → 当天零点毫秒。<b>配错了直接起不来</b>（抛出去），不回落成「不限」——
     * 回落的那个方向正是要防的：一个写错的日期让存量全部卷进来。
     */
    public static Long parseStart(String startDate, String zone) {
        if (startDate == null || startDate.isBlank()) {
            return null;
        }
        ZoneId z;
        try {
            z = ZoneId.of(zone);
        } catch (RuntimeException e) {
            z = ZoneId.of("Asia/Shanghai");
        }
        return LocalDate.parse(startDate.trim()).atStartOfDay(z).toInstant().toEpochMilli();
    }

    @Scheduled(cron = "${shop.job.settle-batch.cron:0 15 * * * *}")
    // 每小时一轮、整点过 15 分（避开整点那一堆任务）。锁 50 分钟 < 间隔：真卡死时下一轮能接手
    @SchedulerLock(name = NAME, lockAtLeastFor = "PT30S", lockAtMostFor = "PT50M")
    public void tick() {
        jobs.run(NAME, () -> run(null).detail());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Bean
    public JobDeclaration settleBatchDeclaration() {
        return new JobDeclaration(NAME, "账期推进",
                "售后期过的结算单定可结算时刻、归入账期批次、到期截批、自查过了变可放款。dry-run 时只报数不写库",
                "shop-app", "0 15 * * * *", true,
                2700, 3000, true, true);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        if (dryRun) {
            var p = batches.preview(startAt);
            String detail = "dry-run（不写库）：定 T2 候选 %d · 待入批 %d · 到期待截批 %d · 待自查 %d"
                    .formatted(p.toMark(), p.toCollect(), p.toClose(), p.toReconcile());
            // WARN 而不是 INFO：dry-run 是上线前的临时态，长期停在这儿等于链路没通
            log.warn("[settle-batch] {}；看过数之后把 shop.job.settle-batch.dry-run 翻成 false", detail);
            return JobResult.ok(p.nothing() ? null : detail);
        }
        /*
         * 四步各自 try：一步炸了不该把后面三步一起带走。
         * 入批炸了，截批那一步仍该把已经入批的到期批次截掉 —— 它们之间只有「顺序」的依赖，
         * 没有「前一步成功」的依赖。
         */
        int[] n = new int[4];
        String[] failed = new String[4];
        n[0] = step(0, failed, "定T2", () -> batches.markSettleable(startAt));
        n[1] = step(1, failed, "入批", () -> batches.collectIntoBatches(startAt));
        n[2] = step(2, failed, "截批", () -> batches.closeDueBatches());
        n[3] = step(3, failed, "自查", () -> batches.reconcileClosedBatches());
        StringBuilder sb = new StringBuilder();
        String[] names = {"定T2", "入批", "截批", "自查"};
        for (int i = 0; i < 4; i++) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(names[i]).append(' ').append(failed[i] != null ? "失败：" + failed[i] : n[i]);
        }
        boolean anyFailed = failed[0] != null || failed[1] != null || failed[2] != null || failed[3] != null;
        boolean anyMoved = n[0] + n[1] + n[2] + n[3] > 0;
        if (anyFailed) {
            return JobResult.failed(sb.toString(), "有步骤失败");
        }
        // 没动任何单时 detail 留 null：JobSupport 用它区分「跑了但没事」与「跑了并做了事」
        return JobResult.ok(anyMoved ? sb.toString() : null);
    }

    private int step(int i, String[] failed, String label, java.util.function.IntSupplier body) {
        try {
            return body.getAsInt();
        } catch (RuntimeException e) {
            log.error("[settle-batch] {} 这一步失败，其余步骤照跑：{}", label, e.toString(), e);
            failed[i] = e.getClass().getSimpleName();
            return 0;
        }
    }
}

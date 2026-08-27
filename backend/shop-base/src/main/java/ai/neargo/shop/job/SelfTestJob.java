package ai.neargo.shop.job;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;

/**
 * 调度链路自检 —— <b>唯一一个可以在生产随便开关、随便点的任务</b>。
 *
 * <h2>为什么需要它</h2>
 * <p>另外 10 个任务全是真业务：要验证「调度器 → HTTP → handler」这条链路通不通，
 * 就得在生产真跑一次关单或对账。<b>那是拿业务数据当探针</b>，
 * 出了事分不清是链路问题还是业务问题。
 *
 * <p>这个任务什么都不做：不读业务表、不写任何行、不发任何消息。
 * 它把<b>收到的 {@link JobInvocation} 原样回显</b>，于是 {@code job_run.detail}
 * 和 {@code job_log} 里那行字本身就是链路的体检报告 ——
 * runId 对得上说明是同一次调用，triggerType 说明是 cron 还是有人点的，
 * bizDate 和 params 说明调度器到底传了什么过来。
 *
 * <h2>它顺带暴露两处已知缺口</h2>
 * <ul>
 *   <li>{@code params} 永远是 <b>{}</b>：{@code job_definition.params} 建了列、
 *       DAO 也读出来了，但 {@code JobRunner} 传下来的是写死的空 Map。
 *       配了不生效，而且不报错。</li>
 *   <li>{@code bizDate} 永远是<b>昨天</b>：对日结对账这是对的默认，
 *       但对每分钟跑的任务没有意义，手动补跑今天的账也做不到。</li>
 * </ul>
 * 这两条修好之前，自检输出里会一直看得见它们 —— <b>比写在文档里更难被忘掉</b>。
 *
 * <h2>默认 cron 是每天一次</h2>
 * <p>不设成每分钟：一个只用来自检的任务不该在日志表里占最大的那块。
 * 要连续观察就在运营端改 cron，改完 30 秒内生效 —— 那本身也是一次链路验证。
 */
@Component
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
public class SelfTestJob implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(SelfTestJob.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** {@code job_run.detail} 是 VARCHAR(500)，截断了就看不出是谁截的。 */
    private static final int DETAIL_MAX = 480;

    @Override
    public String name() {
        return "job-selftest";
    }

    @Bean
    public JobDeclaration jobselftestDeclaration() {
        return new JobDeclaration("job-selftest", "调度链路自检",
                "什么业务都不做，只把收到的调用原样回显 —— 用来验证调度链路本身，"
                        + "不必拿真业务当探针",
                "shop-base",
                "0 50 3 * * *",
                // enabled 只在首次 INSERT 时起作用，且 worker 的 start-disabled
                // 还会再压一道。写 true 表示「这个任务本身适合开着」
                true,
                30,   // timeoutSec：它不做事，30 秒还没回来就是链路有问题
                60,   // lockAtMostSec
                true, // manualTrigger：这是它存在的主要用法
                true  // logEveryRun：自检的价值全在那行日志上，稀疏日志会把它省掉
        );
    }

    @Override
    public JobResult run(JobInvocation in) {
        // params 按键排序：两次输出能直接对比，而 HashMap 的顺序每次可能不同
        Map<String, String> params = new TreeMap<>(in.params());
        String detail = ("自检通过 · 收到 runId=%s · 触发=%s · 业务日期=%s · 参数=%s · 业务侧时刻=%s")
                .formatted(in.runId(), in.type(),
                        in.bizDate() == null ? "(未传)" : in.bizDate(),
                        params.isEmpty() ? "{}（JobRunner 尚未下传 job_definition.params）" : params,
                        LocalDateTime.now().format(TS));
        if (detail.length() > DETAIL_MAX) {
            detail = detail.substring(0, DETAIL_MAX - 3) + "...";
        }
        log.info("[job-selftest] {}", detail);
        return JobResult.ok(detail);
    }
}

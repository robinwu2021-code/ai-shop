package ai.neargo.job.api;

import java.time.LocalDate;
import java.util.Map;

/**
 * 一次调用的输入。worker 发出，业务侧收到。
 *
 * @param runId   这一轮的标识，贯穿 job_log。**业务侧要原样带进自己的日志**，
 *                否则两边的日志对不上，排查时只能靠时间戳猜
 * @param type    怎么触发的
 * @param bizDate <b>业务日期，不是「今天」。</b>日结、对账要的是「2026-08-25 这天的账结了吗」，
 *                而不是「昨晚 3 点那次跑了吗」—— 这两者在补跑时会给出不同答案，
 *                而补跑正是日结最需要的能力。不关心日期的任务忽略它即可
 * @param params  动态任务的参数（同一段代码配出多个任务实例时用）。**不要往日志里打**，
 *                它将来可能带业务标识
 */
public record JobInvocation(
        String runId,
        TriggerType type,
        LocalDate bizDate,
        Map<String, String> params) {

    public JobInvocation {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 不能为空：两边日志靠它对齐");
        }
        if (type == null) {
            throw new IllegalArgumentException("triggerType 不能为空");
        }
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** 取一个参数，没有就给默认值。省得每个 handler 自己判空。 */
    public String param(String key, String defaultValue) {
        return params.getOrDefault(key, defaultValue);
    }
}

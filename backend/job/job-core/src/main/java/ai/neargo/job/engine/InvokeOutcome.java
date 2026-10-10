package ai.neargo.job.engine;

import ai.neargo.job.api.JobStatus;

/**
 * worker 视角的一轮结果。
 *
 * <p><b>为什么不直接复用 {@link ai.neargo.job.api.JobResult}</b>：那个类型刻意
 * <b>禁止</b>业务侧返回 {@code UNREACHABLE} / {@code TIMEOUT}（它给不出这种判断）。
 * 而 worker 恰恰需要表达这两种 —— 它们正是「没收到回答」时唯一说得出口的话。
 * 两个类型的分界，就是「业务说的」与「worker 判的」的分界。
 *
 * @param httpStatus 收到过的 HTTP 状态码；没连上时为 null。排查时它是第一手证据
 */
public record InvokeOutcome(JobStatus status, String detail, String error, Integer httpStatus) {

    public static InvokeOutcome of(JobStatus status, String detail, String error, Integer httpStatus) {
        return new InvokeOutcome(status, detail, error, httpStatus);
    }

    public static InvokeOutcome unreachable(String error) {
        return new InvokeOutcome(JobStatus.UNREACHABLE, "调不通业务系统", error, null);
    }

    public static InvokeOutcome timeout(int timeoutSec) {
        return new InvokeOutcome(JobStatus.TIMEOUT,
                "等了 " + timeoutSec + " 秒没回。**业务侧多半还在跑**，不是失败", null, null);
    }
}

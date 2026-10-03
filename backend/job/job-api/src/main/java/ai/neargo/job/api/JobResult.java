package ai.neargo.job.api;

/**
 * 一次调用的结果。业务侧返回，worker 落库。
 *
 * @param status 只允许业务侧能判定的三个值（SUCCESS / FAILED / SKIPPED）。
 *               UNREACHABLE / TIMEOUT / RUNNING 是 worker 的判断，业务侧给不出来
 * @param detail <b>写给人看的一句话</b>，运营页面直接显示：「关闭 12 单，释放库存 34 件」。
 *               不要写成代码注释的口气，也不要只写「成功」—— 那一行是运营唯一能看到的东西
 * @param error  失败时的原因。**只放异常类名与简短原因，不要放堆栈、不要放业务数据**
 */
public record JobResult(JobStatus status, String detail, String error) {

    public JobResult {
        if (status == null) {
            throw new IllegalArgumentException("status 不能为空");
        }
        if (status == JobStatus.UNREACHABLE || status == JobStatus.TIMEOUT || status == JobStatus.RUNNING) {
            throw new IllegalArgumentException(
                    "业务侧不能返回 " + status + "：那是 worker 在收不到回答时的判断");
        }
    }

    public static JobResult ok(String detail) {
        return new JobResult(JobStatus.SUCCESS, detail, null);
    }

    public static JobResult failed(String detail, String error) {
        return new JobResult(JobStatus.FAILED, detail, error);
    }

    /** 锁没抢到。**不是故障**，不计入连续失败。 */
    public static JobResult skipped() {
        return new JobResult(JobStatus.SKIPPED, "上一轮仍在执行，本轮跳过", null);
    }
}

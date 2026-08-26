package ai.neargo.job.api;

/**
 * <b>一个定时任务的方法体。由业务系统实现，worker 只知道它的名字。</b>
 *
 * <p>为什么任务体必须留在业务系统里，而不是编译进 worker：
 * worker 里若装着业务代码，业务发版后 worker 不重启就跑着上一版的逻辑；
 * 要跟上就得重启 worker —— 而「不重启 worker」正是做这整件事的全部理由。
 * <b>只有任务体不在 worker 里，「发布不打断任务」才成立。</b>
 *
 * <p>实现约定三条：
 * <ol>
 *   <li><b>自己保证并发安全。</b>worker 不排队、不串行化；同名任务的并发保护靠业务侧的锁。
 *       手工调用、迁移期两个 worker 并存，都会真的同时打进来</li>
 *   <li><b>不要抛异常表达业务失败</b>，返回 {@link JobResult#failed} —— 抛异常会变成 HTTP 5xx，
 *       worker 只能记成「调不通」，而那与「跑了但失败了」在排查时是两件事</li>
 *   <li><b>detail 写给人看。</b>它是运营在页面上唯一能看到的东西</li>
 * </ol>
 */
public interface JobHandler {

    /**
     * 代码侧的稳定标识，对应 {@code job_definition.handler_name}。
     *
     * <p><b>与 job_name 不是一回事</b>：handler 是「哪段逻辑」，只能靠发版新增；
     * job 是「哪次调度」，运营可以在页面上用同一个 handler 配出多个（不同 cron、不同参数）。
     * 例如 handler {@code recon-scan} 可以有 {@code recon-scan-wechat}
     * 与 {@code recon-scan-alipay} 两个任务。
     *
     * <p>改名等于换了一个 handler：旧名字的任务会在页面上被标成「代码里已不存在」。
     */
    String name();

    /** 跑一轮。**不要抛异常表达业务失败**，见类注释。 */
    JobResult run(JobInvocation invocation);
}

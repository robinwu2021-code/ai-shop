package ai.neargo.job.worker;

import ai.neargo.job.api.JobDeclaration;

import java.util.List;

/**
 * 从业务系统取回「代码里声明了哪些任务」。
 *
 * <p><b>这个口是「独立」逼出来的。</b>任务声明的源头在业务代码里（中文名、默认 cron、
 * 属于哪个模块，只有代码知道），而业务系统**按设计碰不到 job 库**——
 * 它连连接串都没有。于是只剩一条路：**worker 主动去问**。
 *
 * <p>合并进程内的旧方案里这一步是隐形的（worker 编译进了业务代码，直接拿到 Bean）。
 * 拆开之后它才显形 —— 这类「合并时看不见、拆开才冒出来」的耦合，
 * 正是先做独立进程能提前暴露的东西。
 */
interface JobDeclarationSource {

    /** @return 该业务系统当前代码里声明的全部任务；取不到时抛异常，由调用方决定怎么办 */
    List<JobDeclaration> fetch(String target);
}

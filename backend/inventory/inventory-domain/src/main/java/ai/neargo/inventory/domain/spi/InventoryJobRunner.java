package ai.neargo.inventory.domain.spi;

import java.util.function.Supplier;

/**
 * 定时任务的外壳：计时、记录、兜住异常。
 *
 * <p><b>为什么是接口</b>：ai-shop 内嵌时用它自己的任务运行记录（{@code sys_job_run}）；
 * 独立部署时由 {@code inventory-svc} 提供。领域里的任务只认这个接口，不知道自己跑在哪。
 *
 * @see LoggingJobRunner 没有任何实现时的兜底：只打日志，不吞掉失败的痕迹
 */
public interface InventoryJobRunner {

    /**
     * 跑一轮。
     *
     * @param jobName 与 {@code @SchedulerLock} 的 name 一致
     * @param body    返回「这一轮做了什么」；null 表示这轮没做事
     */
    void run(String jobName, Supplier<String> body);
}

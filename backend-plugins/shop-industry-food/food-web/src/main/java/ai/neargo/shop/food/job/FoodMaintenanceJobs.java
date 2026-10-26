package ai.neargo.shop.food.job;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.food.service.FoodMaintenanceService;
import ai.neargo.shop.food.service.FoodMaintenanceService.Task;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * FOOD 的四个后台补偿任务，交给平台既有任务运行时（外部调度器经 {@code /internal/job/{name}/run} 触发）。
 *
 * <p>它们原先是 food-core 里的 {@code @Scheduled}，而 {@code @EnableScheduling} 只挂在 worker profile ——
 * 生产跑 api,ops，所以<b>从未执行过</b>。现在与 {@link DiningTransactionRecoveryJob} 同形：
 * 任务体在 food-web（api/ops 宿主），工作经 {@link FoodMaintenanceService} 落到原 FOOD 宿主
 * （嵌入=本进程 food-core；独立=food-server 内部入口）。不再挂 {@code @Scheduled}：插件不依赖
 * shop-app 的 JobSupport/ShedLock，排期与防重入统一由调度器和 JobDeclaration 承担。
 */
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
public class FoodMaintenanceJobs {
    static final String SCHEDULED_DISPATCH = "food-scheduled-dispatch";
    static final String QUEUE_NOTICE = "food-queue-notice";
    static final String PRODUCTION_RECOVERY = "food-production-recovery";
    static final String RETURN_AMENDMENT_RECOVERY = "food-return-amendment-recovery";
    private static final String OWNER = "food-core";
    private static final String EVERY_15_SECONDS = "*/15 * * * * *";

    @Bean JobHandler foodScheduledDispatchJob(FoodMaintenanceService service) {
        return new Handler(SCHEDULED_DISPATCH, Task.SCHEDULED_DISPATCH, 100, service);
    }
    @Bean JobHandler foodQueueNoticeJob(FoodMaintenanceService service) {
        return new Handler(QUEUE_NOTICE, Task.QUEUE_NOTICE, 100, service);
    }
    @Bean JobHandler foodProductionRecoveryJob(FoodMaintenanceService service) {
        return new Handler(PRODUCTION_RECOVERY, Task.PRODUCTION_RECOVERY, 50, service);
    }
    @Bean JobHandler foodReturnAmendmentRecoveryJob(FoodMaintenanceService service) {
        return new Handler(RETURN_AMENDMENT_RECOVERY, Task.RETURN_AMENDMENT_RECOVERY, 50, service);
    }

    // 高频任务：logEveryRun=false，只在状态变化或失败时落日志
    @Bean JobDeclaration foodScheduledDispatchDeclaration() {
        return new JobDeclaration(SCHEDULED_DISPATCH, "餐饮预约出餐下厨",
                "到点把预约的原批次推给后厨并补打单据；失败的批次留待下一轮重试，不新建订单",
                OWNER, EVERY_15_SECONDS, true, 60, 120, true, false);
    }
    @Bean JobDeclaration foodQueueNoticeDeclaration() {
        return new JobDeclaration(QUEUE_NOTICE, "餐饮排队叫号通知重发",
                "对到期未送达的叫号通知按原通知号重发，不新建排队号",
                OWNER, EVERY_15_SECONDS, true, 60, 120, true, false);
    }
    @Bean JobDeclaration foodProductionRecoveryDeclaration() {
        return new JobDeclaration(PRODUCTION_RECOVERY, "餐饮出品耗用恢复",
                "按原出品事件重试未落定的原料耗用与冲回，不新增出品事件",
                OWNER, EVERY_15_SECONDS, true, 60, 120, true, false);
    }
    @Bean JobDeclaration foodReturnAmendmentRecoveryDeclaration() {
        return new JobDeclaration(RETURN_AMENDMENT_RECOVERY, "餐饮退菜改单恢复",
                "按原退菜审批重试待后厨确认或结果未知的改单，不新建退款或改单",
                OWNER, EVERY_15_SECONDS, true, 60, 120, true, false);
    }

    static final class Handler implements JobHandler {
        private final String name;
        private final Task task;
        private final int limit;
        private final FoodMaintenanceService service;

        Handler(String name, Task task, int limit, FoodMaintenanceService service) {
            this.name = name;
            this.task = task;
            this.limit = limit;
            this.service = service;
        }

        @Override public String name() { return name; }

        @Override public JobResult run(JobInvocation invocation) {
            try {
                var report = service.run(task, limit);
                String detail = task + ": processed=" + report.processed() + ", pending=" + report.pending();
                return report.pending() == 0 ? JobResult.ok(detail)
                        : JobResult.failed(detail, "FOOD_" + task + "_PENDING");
            } catch (RuntimeException unavailable) {
                return JobResult.failed("FOOD " + task + " unavailable", unavailable.getClass().getSimpleName());
            }
        }
    }
}

package ai.neargo.shop.payclient;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.job.api.JobStatus;
import ai.neargo.shop.pay.points.job.PointsJobServices;
import ai.neargo.shop.pay.points.job.PointsJobServices.Task;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
@ConditionalOnBean(PointsJobServices.class)
public class PointsJobHandlers {
    private final Clock clock;

    public PointsJobHandlers(ObjectProvider<Clock> clocks) {
        clock = clocks.getIfAvailable(Clock::systemUTC);
    }

    @Bean
    public JobHandler pointsStatementConfirmationHandler(PointsJobServices services) {
        return handler("points-statement-confirmation", Task.STATEMENT_CONFIRM, services);
    }
    @Bean
    public JobDeclaration pointsStatementConfirmationDeclaration() {
        return new JobDeclaration("points-statement-confirmation", "积分账单通知与自动确认",
            "可靠通知有财务权限的门店员工；通知后完整异议期届满才自动确认当前无争议版本", "pay", "0 */5 * * * *", false, 90, 180, true, false);
    }
    @Bean
    public JobHandler pointsSourceHandler(PointsJobServices services) {
        return handler("points-source-reconcile", Task.SOURCE, services);
    }

    @Bean
    public JobDeclaration pointsSourceDeclaration() {
        return new JobDeclaration(
                "points-source-reconcile",
                "积分事实补偿",
                "按可信源水位补齐事实；源订单适配未接入时明确跳过",
                "pay",
                "0 */5 * * * *",
                false,
                90,
                180,
                true,
                false);
    }

    @Bean
    public JobHandler pointsDispatchHandler(PointsJobServices services) {
        return handler("points-event-dispatch", Task.DISPATCH, services);
    }

    @Bean
    public JobDeclaration pointsDispatchDeclaration() {
        return new JobDeclaration(
                "points-event-dispatch",
                "积分事件投递",
                "处理持久事件与原资金指令；未知结果退避查询，失败进入待办",
                "pay",
                "*/5 * * * * *",
                false,
                90,
                180,
                true,
                false);
    }

    @Bean
    public JobHandler pointsRecoveryHandler(PointsJobServices services) {
        return handler("points-funds-recovery", Task.RECOVERY, services);
    }

    @Bean
    public JobDeclaration pointsRecoveryDeclaration() {
        return new JobDeclaration(
                "points-funds-recovery",
                "积分资金恢复扫描",
                "轮换扫描原付款、专项划拨和退款意图，持久化恢复任务",
                "pay",
                "*/15 * * * * *",
                false,
                90,
                180,
                true,
                false);
    }

    @Bean
    public JobHandler pointsLifecycleHandler(PointsJobServices services) {
        return handler("points-lifecycle", Task.LIFECYCLE, services);
    }

    @Bean
    public JobDeclaration pointsLifecycleDeclaration() {
        return new JobDeclaration(
                "points-lifecycle",
                "积分生命周期扫描",
                "发送到期提醒；仅配置并认证收入账户后准备到期划拨，不推断退款窗口关闭",
                "pay",
                "0 */5 * * * *",
                false,
                90,
                180,
                true,
                false);
    }

    @Bean
    public JobHandler pointsReconHandler(PointsJobServices services) {
        return handler("points-kernel-recon", Task.RECON, services);
    }

    @Bean
    public JobDeclaration pointsReconDeclaration() {
        return new JobDeclaration(
                "points-kernel-recon",
                "积分新账对账",
                "核对凭证、头寸、预留与实际资金证据；只记差异，不改余额",
                "pay",
                "0 */10 * * * *",
                false,
                90,
                180,
                true,
                false);
    }

    @Bean
    public JobHandler pointsProjectionHandler(PointsJobServices services) {
        return handler("points-stats-project", Task.STATS_PROJECT, services);
    }

    @Bean
    public JobDeclaration pointsProjectionDeclaration() {
        return new JobDeclaration(
                "points-stats-project",
                "积分统计投影",
                "按变更日重建统计并确认原凭证事件，重投不重复累计",
                "pay",
                "*/15 * * * * *",
                false,
                90,
                180,
                true,
                false);
    }

    @Bean
    public JobHandler pointsRebuildHandler(PointsJobServices services) {
        return handler("points-stats-rebuild", Task.STATS_REBUILD, services);
    }

    @Bean
    public JobDeclaration pointsRebuildDeclaration() {
        return new JobDeclaration(
                "points-stats-rebuild",
                "积分日统计重建",
                "重建指定业务日统计，包含跳过发放及门店和用户维度",
                "pay",
                "0 30 1 * * *",
                false,
                90,
                180,
                true,
                false);
    }

    @Bean
    public JobHandler pointsPlansHandler(PointsJobServices services) {
        return handler("points-topup-plans", Task.PLANS, services);
    }

    @Bean
    public JobDeclaration pointsPlansDeclaration() {
        return new JobDeclaration(
                "points-topup-plans",
                "积分备款计划",
                "补齐到期计划周期，只生成待审核工作，不自动审批或虚构到账",
                "pay",
                "0 */10 * * * *",
                false,
                90,
                180,
                true,
                false);
    }

    @Bean
    public JobHandler pointsStatementBuildHandler(PointsJobServices services) {
        return handler("points-statement-build",Task.STATEMENTS,services);
    }

    @Bean
    public JobDeclaration pointsStatementBuildDeclaration() {
        return new JobDeclaration("points-statement-build","积分账单分批构建",
                "每页最多200条，按固定终局高水位续跑并发布账单；不发起真实兑付",
                "pay","0 */5 * * * *",false,90,180,true,false);
    }

    private JobHandler handler(String name, Task task, PointsJobServices services) {
        return new JobHandler() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public JobResult run(JobInvocation invocation) {
                try {
                    int limit = Integer.parseInt(invocation.param("limit", "50"));
                    if (limit < 1 || limit > 200) {
                        return JobResult.failed("每批条数必须为 1–200", "POINTS_BATCH_INVALID");
                    }
                    String type = invocation.param("typeCode", "");
                    if (type.isBlank() && task != Task.DISPATCH) {
                        return new JobResult(JobStatus.SKIPPED, "尚未配置积分类别，本轮未执行", null);
                    }
                    var result =
                            services.run(
                                    task,
                                    type.isBlank() ? null : type,
                                    limit,
                                    clock.millis(),
                                    invocation.bizDate());
                    String detail =
                            "已处理 "
                                    + result.processed()
                                    + " 项，等待 "
                                    + result.deferred()
                                    + " 项，待人工 "
                                    + result.manual()
                                    + " 项";
                    if (result.manual() > 0) {
                        return JobResult.failed(detail, "POINTS_MANUAL_REVIEW");
                    }
                    return result.available()
                            ? JobResult.ok(detail)
                            : new JobResult(JobStatus.SKIPPED, "依赖尚未就绪或核查未完整。" + detail, null);
                } catch (RuntimeException failure) {
                    String reason = failure.getMessage();
                    String code =
                            reason != null && reason.matches("POINTS_[A-Z_]+")
                                    ? reason
                                    : "UNEXPECTED";
                    return JobResult.failed("本轮未完成：" + failure.getClass().getSimpleName(), code);
                }
            }
        };
    }
}

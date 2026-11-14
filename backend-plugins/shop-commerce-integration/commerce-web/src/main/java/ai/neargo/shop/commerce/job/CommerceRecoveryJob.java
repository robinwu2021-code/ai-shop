package ai.neargo.shop.commerce.job;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.commerce.service.CommerceRecoveryService;
import ai.neargo.shop.commerce.service.ResourceMappingVerificationService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** 交由平台Job调度；默认关闭，无重复的本地Scheduled触发器。 */
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
public class CommerceRecoveryJob implements JobHandler {
    private final CommerceRecoveryService service;

    private final ResourceMappingVerificationService mappings;

    public CommerceRecoveryJob(
            CommerceRecoveryService service, ResourceMappingVerificationService mappings) {
        this.mappings = mappings;
        this.service = service;
    }

    public String name() {
        return "commerce-recovery";
    }

    @Bean
    public JobDeclaration commerceRecoveryDeclaration() {
        return new JobDeclaration(
                name(),
                "渠道同步恢复",
                "合并自动核验、排队发送与原任务查询",
                "commerce",
                "0 */1 * * * *",
                false,
                60,
                120,
                true,
                true);
    }

    public JobResult run(JobInvocation invocation) {
        var result = service.recoverDue(50);
        var mappingResult = mappings.verifyDue(50);
        String detail =
                "发送 "
                        + result.dispatched()
                        + " 项，查询 "
                        + result.queried()
                        + " 项，自动排队 "
                        + result.automatic()
                        + " 项，等待 "
                        + result.pending()
                        + " 项，异常 "
                        + result.failed()
                        + " 项";
        detail +=
                "；映射核验通过 "
                        + mappingResult.verified()
                        + " 项，待核验 "
                        + mappingResult.pending()
                        + " 项，异常 "
                        + mappingResult.failed()
                        + " 项";
        return result.failed() + mappingResult.failed() > 0
                ? JobResult.failed(detail, "部分任务未完成，查看同步任务状态")
                : JobResult.ok(detail);
    }
}

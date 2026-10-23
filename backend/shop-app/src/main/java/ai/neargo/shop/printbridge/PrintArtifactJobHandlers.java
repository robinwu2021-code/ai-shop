package ai.neargo.shop.printbridge;

import ai.neargo.job.api.*;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.export.*;
import ai.neargo.shop.print.simulation.SimulationRunner;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = {"shop.job.enabled", "shop.print.enabled"},
        havingValue = "true")
public class PrintArtifactJobHandlers {
    @Bean
    public JobHandler printArtifactMaintenance(
            SimulationRunner s,
            PrintExportWorker e,
            PrintExportRecoveryJob r,
            PrintExportCleanupJob c) {
        return new JobHandler() {
            public String name() {
                return "print-artifact-maintenance";
            }

            public JobResult run(JobInvocation invocation) {
                try {
                    var budget =
                            new PrintWorkerBudget(20, Duration.ofSeconds(5), "print-artifact-job");
                    var recovered = r.recover(budget);
                    var simulated = s.runNext(budget);
                    var exported = e.generateNext(budget);
                    var cleaned = c.expire(budget);
                    int failures =
                            recovered.failed()
                                    + simulated.failed()
                                    + exported.failed()
                                    + cleaned.failed();
                    return failures == 0
                            ? JobResult.ok("打印模拟/PDF维护完成")
                            : JobResult.failed("打印模拟/PDF存在待处理失败", "PRINT_ARTIFACT_ATTENTION");
                } catch (RuntimeException ex) {
                    return JobResult.failed("打印模拟/PDF维护未完成", "PRINT_ARTIFACT_FAILED");
                }
            }
        };
    }

    @Bean
    public JobDeclaration printArtifactMaintenanceDeclaration() {
        return new JobDeclaration(
                "print-artifact-maintenance",
                "打印模拟及PDF维护",
                "处理隔离模拟、PDF生成、重启恢复及过期清理",
                "print",
                "*/10 * * * * *",
                false,
                60,
                120,
                true,
                false);
    }
}

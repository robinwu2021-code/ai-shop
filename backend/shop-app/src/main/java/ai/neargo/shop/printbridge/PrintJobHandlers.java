package ai.neargo.shop.printbridge;

import ai.neargo.job.api.*;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.printing.center.dispatch.*;
import ai.neargo.shop.printing.center.monitor.PrintMaintenanceService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = {"shop.job.enabled", "shop.print.enabled"},
        havingValue = "true")
public class PrintJobHandlers {
    @Bean
    public JobHandler printLifecycleHandler(
            PrintDispatchService d, PrintReceiptService r, PrintMaintenanceService m) {
        return new JobHandler() {
            public String name() {
                return "print-lifecycle";
            }

            public JobResult run(JobInvocation invocation) {
                try {
                    var budget =
                            new PrintWorkerBudget(25, Duration.ofSeconds(8), "print-lifecycle-job");
                    var a = r.applyPending(budget);
                    var b = d.dispatchCloud(budget);
                    var c = m.maintain(budget);
                    return a.failed() + b.failed() + c.failed() == 0
                            ? JobResult.ok("打印投递、结果核查及巡检完成")
                            : JobResult.failed("打印存在待核查项目", "PRINT_ATTENTION");
                } catch (RuntimeException ex) {
                    return JobResult.failed("打印维护未完成", "PRINT_MAINTENANCE_FAILED");
                }
            }
        };
    }

    @Bean
    public JobDeclaration printLifecycleDeclaration() {
        return new JobDeclaration(
                "print-lifecycle",
                "打印投递与容错巡检",
                "逐机仲裁投递、可靠回执、漏建对账及故障通知",
                "print",
                "*/10 * * * * *",
                false,
                60,
                120,
                true,
                false);
    }
}

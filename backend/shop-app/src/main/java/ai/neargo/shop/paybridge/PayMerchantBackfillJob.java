package ai.neargo.shop.paybridge;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * 收款商户回填 B1–B4 的调度壳（TDD-支付服务-I4进件与收款商户 S3）：<b>只手动触发</b>，全量跑，幂等。
 * 冲突逐条打日志；有冲突就记失败，等人工处理完再跑。
 */
@Component
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
public class PayMerchantBackfillJob implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(PayMerchantBackfillJob.class);

    private final PayMerchantBackfillRunner runner;

    public PayMerchantBackfillJob(PayMerchantBackfillRunner runner) {
        this.runner = runner;
    }

    @Override
    public String name() {
        return "pay-merchant-backfill";
    }

    @Bean
    public JobDeclaration payMerchantBackfillDeclaration() {
        return new JobDeclaration("pay-merchant-backfill", "收款商户回填（B1–B4）",
                "把 mch_payment_merchant 迁成进件单、收款商户、门店绑定与开通产品；冲突进报告不自动取舍。幂等，只手动触发",
                "shop-app", "0 0 4 1 1 ?", false, 3600, 3600, true, true);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        PayMerchantBackfillRunner.Report r = runner.run(List.of());
        r.conflicts().forEach(c -> log.warn("[pay-merchant-backfill] {}", c));
        String detail = "收款商户 %d｜新绑定 %d｜冲突 %d".formatted(r.merchants(), r.bindings(), r.conflicts().size());
        return r.conflicts().isEmpty() ? JobResult.ok(detail)
                : JobResult.failed(detail, "冲突见日志 [pay-merchant-backfill]：Q2 补默认门店、Q3 选定门店的号、METHOD 补产品后重跑");
    }
}

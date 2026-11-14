package ai.neargo.shop.paybridge;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * 比对 C07 的调度壳（TDD-支付服务-I4进件与收款商户 S5）：<b>只手动触发</b>、全量比。每条差异一行日志 {@code [pay-merchant-compare]}
 * 留档；人工确认过的差异把 {@link PayMerchantComparator.Diff#key()} 填进 {@code shop.pay.merchant.compare.confirmed}。
 * 未确认差异为 0 记成功，这就是读切的放行条件。
 */
@Component
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
public class PayMerchantCompareJob implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(PayMerchantCompareJob.class);

    private final PayMerchantComparator comparator;
    private final Set<String> confirmed;

    public PayMerchantCompareJob(PayMerchantComparator comparator,
                                 @Value("${shop.pay.merchant.compare.confirmed:}") List<String> confirmed) {
        this.comparator = comparator;
        this.confirmed = Set.copyOf(confirmed.stream().filter(k -> !k.isBlank()).toList());
    }

    @Override
    public String name() {
        return "pay-merchant-compare";
    }

    @Bean
    public JobDeclaration payMerchantCompareDeclaration() {
        return new JobDeclaration("pay-merchant-compare", "收款商户新旧解析比对（C07）",
                "每个门店 × 渠道比旧解析与支付新模型的收款号、子商户号、资金路径；未确认差异为 0 才可读切。只手动触发",
                "shop-app", "0 0 4 1 1 ?", false, 3600, 3600, true, true);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        PayMerchantComparator.Result r = comparator.compare(List.of(), confirmed);
        r.diffs().forEach(d -> log.warn("[pay-merchant-compare] {} key={}", d, d.key()));
        String detail = "比对 %d 个门店 × 渠道｜差异 %d｜未确认 %d".formatted(r.checked(), r.diffs().size(), r.unconfirmed());
        return r.readSwitchAllowed() ? JobResult.ok(detail)
                : JobResult.failed(detail, "差异见日志 [pay-merchant-compare]：修数据后重跑，或确认后把 key 填进 shop.pay.merchant.compare.confirmed");
    }
}

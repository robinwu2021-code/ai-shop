package ai.neargo.shop.paybridge;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.pay.onboarding.service.OnboardingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * 支付进件轮询的调度壳（TDD-支付服务-I4进件与收款商户 S7）：与旧 {@code applyment-poll} 同节奏，
 * 只推进支付自己的进件单 —— 回填来的 LEGACY 单仍由旧轮询推进、经双写镜像过来。
 */
@Component
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
public class PayOnboardingPollJob implements JobHandler {

    @Value("${shop.job.pay-onboarding-poll.batch:200}")
    private int batch;

    private final OnboardingService onboardings;

    public PayOnboardingPollJob(OnboardingService onboardings) {
        this.onboardings = onboardings;
    }

    @Override
    public String name() {
        return "pay-onboarding-poll";
    }

    @Bean
    public JobDeclaration payOnboardingPollDeclaration() {
        return new JobDeclaration("pay-onboarding-poll", "支付进件轮询",
                "主动去渠道问支付进件单批了没有：通过即产出收款商户并绑门店，驳回把原因落在那一版上",
                "shop-app", "0 */30 * * * *", true, 1200, 1500, true, true);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        OnboardingService.PollResult r = onboardings.poll(batch);
        String detail = "在途 %d｜出结果 %d｜失败 %d".formatted(r.scanned(), r.settled(), r.failed());
        return r.failed() > 0 ? JobResult.failed(detail, "失败的进件单见日志 [onboarding]") : JobResult.ok(detail);
    }
}

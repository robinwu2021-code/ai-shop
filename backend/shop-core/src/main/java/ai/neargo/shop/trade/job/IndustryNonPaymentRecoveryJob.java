package ai.neargo.shop.trade.job;

import ai.neargo.shop.trade.service.IndustryFulfillmentService;
import ai.neargo.shop.trade.service.IndustryOrderAmendmentService;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Replays only durable original fulfillment and unpaid receivable intents. */
@Component
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
public class IndustryNonPaymentRecoveryJob {
    private final IndustryFulfillmentService fulfillment;
    private final IndustryOrderAmendmentService amendments;

    public IndustryNonPaymentRecoveryJob(
            IndustryFulfillmentService fulfillment, IndustryOrderAmendmentService amendments) {
        this.fulfillment = fulfillment;
        this.amendments = amendments;
    }

    @Scheduled(fixedDelayString = "${shop.job.industry-non-payment-recovery.delay-ms:15000}")
    @SchedulerLock(name = "industry-non-payment-recovery", lockAtMostFor = "PT2M")
    public void run() {
        fulfillment.recover(100);
        amendments.recover(100);
    }
}

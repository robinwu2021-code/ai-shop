package ai.neargo.shop.merchantbridge;

import ai.neargo.shop.merchant.service.StoreEntityClaimService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Business rows are the sole recovery queue. No new delivery framework or automatic unfreeze. */
@Component
@Profile("worker")
public class StoreEntityClaimRecovery {
    private final StoreEntityClaimService claims;
    public StoreEntityClaimRecovery(StoreEntityClaimService claims) {this.claims=claims;}
    @Scheduled(fixedDelay=30000)
    @SchedulerLock(name="store-entity-claim-recovery", lockAtMostFor="PT10M")
    public void recover() {claims.recoverPending();}
}

package ai.neargo.shop.spi.promotion;

import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.entitlement.SourceRef;
import java.time.Instant;
import java.util.List;

/** Core owns activity eligibility and marketing budget; pay owns issuance capacity and consumer rights. */
public interface ActivityParticipationPort {
    ParticipationPermit reserve(EntitlementContext context, ReserveActivity command);

    ParticipationView confirm(EntitlementContext context, ConfirmParticipation command);

    ParticipationView release(EntitlementContext context, ReleaseParticipation command);

    /** Query original participation; an expired reservation is not proof pay has aborted. */
    ParticipationView query(EntitlementContext context, SourceRef source);

    enum State { NOT_FOUND, RESERVED, CONFIRMED, RELEASED, EXCEPTION }

    record ReserveActivity(SourceRef sourceRef, String activityNo, String versionNo, String tierNo, String userNo,
                           String entityNo, String storeNo, String currency, int quantity) {
        public ReserveActivity {
            ContractChecks.present(sourceRef);
            ContractChecks.id(activityNo);
            ContractChecks.id(versionNo);
            ContractChecks.optionalId(tierNo);
            ContractChecks.id(userNo);
            ContractChecks.id(entityNo);
            ContractChecks.id(storeNo);
            ContractChecks.currency(currency);
            ContractChecks.require(quantity > 0);
        }
    }

    record ParticipationPermit(String reservationNo, SourceRef sourceRef, String activityNo, String versionNo,
                               String tierNo, String userNo, String entityNo, String storeNo, String currency,
                               Long payAmountMinor, Long bonusAmountMinor, List<String> benefitRefs,
                               RedemptionScope redemptionScope, String snapshotHash, Instant issuedAt) {
        public ParticipationPermit {
            ContractChecks.id(reservationNo);
            new ReserveActivity(sourceRef, activityNo, versionNo, tierNo, userNo, entityNo, storeNo, currency, 1);
            ContractChecks.require((payAmountMinor == null) == (bonusAmountMinor == null));
            if (payAmountMinor != null) {
                ContractChecks.require(payAmountMinor > 0 && bonusAmountMinor >= 0);
                Math.addExact(payAmountMinor, bonusAmountMinor);
            }
            benefitRefs = List.copyOf(ContractChecks.present(benefitRefs));
            benefitRefs.forEach(ContractChecks::id);
            ContractChecks.present(redemptionScope);
            ContractChecks.hash(snapshotHash);
            ContractChecks.present(issuedAt);
        }
    }

    /** payFactNo identifies a committed grant/recharge that core verifies, never a client-declared success. */
    record ConfirmParticipation(String reservationNo, SourceRef sourceRef, String payFactNo) {
        public ConfirmParticipation {
            ContractChecks.id(reservationNo);
            ContractChecks.present(sourceRef);
            ContractChecks.id(payFactNo);
        }
    }

    /** payAbortNo must resolve to a durable abort that prevents later issuance. */
    record ReleaseParticipation(String reservationNo, SourceRef sourceRef, String payAbortNo) {
        public ReleaseParticipation {
            ContractChecks.id(reservationNo);
            ContractChecks.present(sourceRef);
            ContractChecks.id(payAbortNo);
        }
    }

    record ParticipationView(String reservationNo, State status, SourceRef sourceRef, long heldQuota,
                             long heldBudgetMinor, String currency, String confirmedFactNo) {
        public ParticipationView {
            ContractChecks.present(status);
            ContractChecks.present(sourceRef);
            ContractChecks.require(heldQuota >= 0 && heldBudgetMinor >= 0);
            if (status != State.NOT_FOUND) {
                ContractChecks.id(reservationNo);
                ContractChecks.currency(currency);
            }
            if (status == State.CONFIRMED) {
                ContractChecks.id(confirmedFactNo);
            }
        }
    }
}

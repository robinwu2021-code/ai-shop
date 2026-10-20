package ai.neargo.shop.pay.entitlement.dto;

import java.util.Set;
import java.util.List;
import java.time.Instant;
import ai.neargo.shop.spi.entitlement.ContractChecks;

/** Operator-only lifecycle commands retain a reason without exposing internal ledger or authorization facts. */
public final class LifecycleViews {
    private LifecycleViews() { }
    public record FreezeCommand(long expectedAssetRevision,String action,String reason) {
        public FreezeCommand {
            if(expectedAssetRevision<0 || action==null || !Set.of("FREEZE","UNFREEZE").contains(action)
                    || reason==null || reason.isBlank() || reason.length()>500) {
                throw new IllegalArgumentException("Lifecycle action, revision and reason required");
            }
        }
    }
    public record FreezeView(String instanceNo,String status,long revision,Integer remainingUnits,int reservedUnits) { }
    /** Stored exclusively in pay command audit; the public route returns result only. */
    public record FreezeAudit(FreezeView result,String action,String reason,String beforeStatus,long expectedRevision) { }
    public record ExpireResult(int changed) { }
    public record ExpiryReminder(String instanceNo, String userNo, Instant effectiveUntil) {
        public ExpiryReminder {
            ContractChecks.id(instanceNo); ContractChecks.id(userNo); ContractChecks.present(effectiveUntil);
        }
    }
    public record ReminderPage(List<ExpiryReminder> records, long nextId) {
        public ReminderPage {
            records = List.copyOf(ContractChecks.present(records));
            ContractChecks.require(records.size() <= 100 && nextId >= 0);
        }
    }
}

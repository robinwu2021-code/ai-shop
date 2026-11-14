package ai.neargo.commerce.core.offline;

import ai.neargo.commerce.api.offline.OfflinePurchaseFacade.*;
import ai.neargo.commerce.api.port.PrivateEvidencePort;
import java.util.*;

/** Business workflow only; every operation runs in the owning commerce UoW. */
public interface OfflineRepository {
    record Evidence(String assetKey, PrivateEvidencePort.Scope scope, long createdAt) {}
    /** {@code received}: platform-recorded amount from the operator review; absent in older snapshots. */
    record Claim(ClaimCommand command, String submitterNo, long submittedAt, String status, String reviewer, String reason,
            ai.neargo.commerce.api.common.Money received) {
        public Claim(ClaimCommand command, String submitterNo, long submittedAt, String status, String reviewer, String reason) {
            this(command,submitterNo,submittedAt,status,reviewer,reason,null);
        }
        public Claim reviewed(String status,String reviewer,String reason) { return new Claim(command,submitterNo,submittedAt,status,reviewer,reason,received); }
        public Claim reviewed(String status,String reviewer,String reason,ai.neargo.commerce.api.common.Money received) {
            return new Claim(command,submitterNo,submittedAt,status,reviewer,reason,received);
        }
        /** Buyer-safe projection: the buyer's own reference and receipts; amount only once money was recorded, note only when it explains a refusal. */
        public ClaimView view() {
            boolean recorded="CONFIRMED".equals(status) || "MISMATCHED".equals(status);
            boolean explained="DECLINED".equals(status) || "MISMATCHED".equals(status);
            return new ClaimView(command.claimNo(),status,command.declaredAmount(),command.declaredPaidAt(),java.time.Instant.ofEpochMilli(submittedAt),
                    command.bankReference(),command.evidenceKeys(),recorded?received:null,explained?reason:null);
        }
    }
    record Action(String actionNo,String kind,ai.neargo.commerce.api.mall.OrderQuery order,String operatorNo,long acceptedAt,
            ReviewCommand review,VoidCommand reconciliation,ai.neargo.commerce.api.port.PaymentIntentCommand payment,
            String status,String paymentNo,String rejection,SupplementCommand supplement) {
        public Action(String actionNo,String kind,ai.neargo.commerce.api.mall.OrderQuery order,String operatorNo,long acceptedAt,
                ReviewCommand review,VoidCommand reconciliation,ai.neargo.commerce.api.port.PaymentIntentCommand payment,
                String status,String paymentNo,String rejection) {
            this(actionNo,kind,order,operatorNo,acceptedAt,review,reconciliation,payment,status,paymentNo,rejection,null);
        }
        public Action(String actionNo,String kind,ai.neargo.commerce.api.mall.OrderQuery order,String operatorNo,long acceptedAt,
                ReviewCommand review,VoidCommand reconciliation,ai.neargo.commerce.api.port.PaymentIntentCommand payment,
                String status,String paymentNo) {
            this(actionNo,kind,order,operatorNo,acceptedAt,review,reconciliation,payment,status,paymentNo,null);
        }
        public ActionView view() { return new ActionView(actionNo,status,paymentNo); }
        public Action completed(String status,String paymentNo) { return new Action(actionNo,kind,order,operatorNo,acceptedAt,review,reconciliation,payment,status,paymentNo,null); }
        /** Definite pay-domain refusal: terminal, so it never blocks later actions or recovery. */
        public Action rejected(String reason) { return new Action(actionNo,kind,order,operatorNo,acceptedAt,review,reconciliation,payment,"REJECTED",null,reason); }
    }
    Optional<Action> action(String tenant,String orderNo,String actionNo);
    List<Action> pending(String tenant,String orderNo);
    default List<Action> actions(String tenant,String orderNo) { throw new UnsupportedOperationException(); }
    /** Distinct order numbers with ACCEPTED actions after the cursor, ordered. */
    List<String> pendingOrders(String tenant,String afterOrderNo,int limit);
    void saveAction(Action action);
    void reserve(Evidence evidence);
    Optional<Evidence> evidence(String tenant, String key);
    List<String> retained(String tenant);
    Optional<Claim> claim(String tenant, String orderNo, String claimNo);
    void saveClaim(Claim claim);
    List<Claim> claims(String tenant, String orderNo);
    List<Claim> submittedClaims(String tenant, String afterOrderNo, String afterClaimNo, int limit);
}

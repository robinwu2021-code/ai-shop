package ai.neargo.commerce.core.offline;

import ai.neargo.commerce.api.offline.OfflinePurchaseFacade.*;
import ai.neargo.commerce.api.port.PrivateEvidencePort;
import java.util.*;

/** Business workflow only; every operation runs in the owning commerce UoW. */
public interface OfflineRepository {
    record Evidence(String assetKey, PrivateEvidencePort.Scope scope, long createdAt) {}
    record Claim(ClaimCommand command, String submitterNo, long submittedAt, String status, String reviewer, String reason) {
        public ClaimView view() { return new ClaimView(command.claimNo(),status,command.declaredAmount(),command.declaredPaidAt(),java.time.Instant.ofEpochMilli(submittedAt)); }
    }
    record Action(String actionNo,String kind,ai.neargo.commerce.api.mall.OrderQuery order,String operatorNo,long acceptedAt,
            ReviewCommand review,VoidCommand reconciliation,ai.neargo.commerce.api.port.PaymentIntentCommand payment,
            String status,String paymentNo,String rejection) {
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
    void saveAction(Action action);
    void reserve(Evidence evidence);
    Optional<Evidence> evidence(String tenant, String key);
    List<String> retained(String tenant);
    Optional<Claim> claim(String tenant, String orderNo, String claimNo);
    void saveClaim(Claim claim);
}

package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.common.*;
import java.time.Instant;
import java.util.*;

/** Shared business coordinator. Callers MUST authorize the owning order and verify REFUND_PROOF before writes. */
public interface OfflineCashRefundPort {
    record Target(StoreRef store,String orderNo,boolean allowUnfulfilledMatchedCash) {}
    record Request(String refundNo,String paymentNo,String reason) {}
    record Payout(String actionNo,Money actualAmount,Instant paidOutAt,String payeeReference,
                  String bankReference,String evidenceKey) {}
    record Action(String actionNo,String status,String rejection) {}
    /** Buyer-safe: never includes private platform proof, payee identifiers or bank references. */
    record View(String refundNo,String paymentNo,Money amount,String status,String reason,
                String rejection,List<Action> actions) { public View {actions=List.copyOf(actions);} }
    List<View> list(Target target);
    View request(Target target,String operatorNo,Request command);
    /** Trusted owning workflow only: attach its already-approved immutable native late refund command. */
    View adopt(Target target,String operatorNo,PaymentRefundCommand original);
    View payout(Target target,String operatorNo,String refundNo,Payout command);
    View recover(Target target,String refundNo);
    record Sweep(int visited,int failed,String nextRefundNo) {}
    Sweep recoverPending(String tenantNo,String afterRefundNo,int limit);
    /** Empty for a refund owned by another workflow; true only after a matching verified completion. */
    Optional<Boolean> recoverHandoff(String tenantNo,String orderNo,String refundNo);
}

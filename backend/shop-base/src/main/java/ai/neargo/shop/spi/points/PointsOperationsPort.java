package ai.neargo.shop.spi.points;
/** Trusted server-side API. Source adapters own authorization; this API never accepts balance edits. */
public interface PointsOperationsPort {
    enum Source {
        TRADE
    }
    enum Holder {
        USER, STORE
    }
    enum Action {
        EARN, HOLD, RELEASE, FREEZE, REFUND_PREPARE, REFUND_AUTHORIZE, REFUND_CONFIRM, REFUND_ABORT, REFUND_COMPENSATE, LIFECYCLE, REFUND_FACT, CLOSURE, ZERO_CASH
    }
    enum Status {
        ACCEPTED, PROCESSING, SUCCEEDED, SKIPPED, RETRYABLE, MANUAL, REJECTED
    }
    record Operation(Source source, String businessNo, String operationNo, String typeCode) {
    }
    record Context(String requestId, Operation operation, String currency) {
    }
    record Receipt(String operationKey, Status status, String domainState, String reference, String reason) {
    }
    record Balance(String typeCode, String currency, Holder holderType, String holderNo, boolean exists, long pendingPoints, long availablePoints, long heldPoints, boolean frozen, Long expireAt, long version) {
    }
    record Earning(PointsEarnPort.GrantCommand command, PointsFactPort.LifecycleFact paidFact) {
    }
    record AccountControl(String userNo, long expectedVersion, boolean frozen, String actor, String remark) {
    }
    record RefundAuthorization(String preparationNo, String inputHash, long revision) {
    }
    Balance balance(String typeCode, Holder holder, String holderNo);
    Receipt status(Operation operation, Action action);
    Receipt submitEarning(Context context, Earning earning);
    PointsRedeemPort.RedeemQuote quote(String typeCode, String currency, PointsRedeemPort.RedeemQuery query);
    PointsRedeemPort.HoldOutcome hold(Context context, PointsRedeemPort.HoldCommand command);
    PointsRedeemPort.HoldOutcome release(Context context, PointsRedeemPort.ReleaseCommand command);
    Receipt controlAccount(Context context, AccountControl control);
    PointsRefundPort.RefundPreparation prepareRefund(Context context, PointsRefundPort.RefundCommand command);
    PointsRefundPort.CashDecision authorizeRefund(Context context, RefundAuthorization authorization);
    PointsRefundPort.RefundOutcome confirmRefund(Context context, PointsRefundPort.RefundConfirmation confirmation);
    Receipt abortRefund(Context context, PointsRefundPort.RefundAbort abort);
    Receipt compensateRefund(Context context, PointsRefundPort.RefundAbort abort, long revision);
    PointsRefundPort.RefundRecoveryStatus refundStatus(Context context, String preparationNo, String inputHash);
    Receipt acceptLifecycle(Context context, PointsFactPort.LifecycleFact fact);
    Receipt acceptRefundFact(Context context, PointsFactPort.RefundConfirmationFact fact);
    Receipt acceptClosure(Context context, PointsFactPort.RefundClosureFact fact);
    Receipt acceptZeroCash(Context context, PointsRefundPort.ZeroCashCompletionFact fact);
}

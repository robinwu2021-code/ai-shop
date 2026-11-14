package ai.neargo.shop.pay.service;

import ai.neargo.shop.spi.settle.PaymentOperationPort.OfflinePaymentReceiptReq;
import ai.neargo.shop.spi.settle.PaymentOperationPort.PaymentOperationReq;
import ai.neargo.shop.spi.settle.PaymentOperationPort.PaymentOperationView;
import ai.neargo.shop.spi.settle.SettlePort;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Short pay-only transactions. Gateway and trade-owner calls belong outside this service. */
public interface PaymentOperationService {
    /** Persist the accepted instruction before any ledger open or external send. */
    PaymentOperationView prepare(PaymentOperationReq request);

    /**
     * Atomically attach the original ledger row and its canonical operation under the order guard.
     */
    PaymentOperationView open(String operationNo);

    /** Read the original mapping without creating or dispatching an attempt. */
    PaymentOperationView find(String operationNo);

    /** Bring existing trade initiation through the same canonical dispatch arbitration. */
    PaymentOperationView prepareLegacy(SettlePort.PaymentOpen payment);

    /**
     * Commit the sole send claim before returning a ticket to lock-free channel I/O; caller
     * transactions are forbidden so a canonical-binding race can release locks before retry.
     */
    Optional<DispatchTicket> claimSend(String operationNo);

    /** Retain replayable parameters only for the original committed send claim. */
    PaymentOperationView recordPayable(
            DispatchTicket ticket, Map<String, String> params, Instant expiresAt);

    /** Preserve uncertain acceptance so later reads query the original number. */
    PaymentOperationView recordUnknown(DispatchTicket ticket, String reasonCode);

    /** Close only a locally proven unsent attempt, allowing an explicit new request. */
    PaymentOperationView rejectNotSent(DispatchTicket ticket, NotSentReason reason);

    /**
     * Record the authorized external receipt and immutable evidence in the same pay transaction.
     */
    PaymentOperationView confirmOffline(OfflinePaymentReceiptReq request);

    /**
     * Observe the real ledger in an owned transaction, then read the committed mapping; caller
     * transactions are forbidden so canonical-binding retries cannot retain a stale snapshot.
     */
    PaymentOperationView observeReceipt(String operationNo);

    /** Prevent legacy close or reconciliation from discarding a potentially accepted payment. */
    boolean protectsUncertainAttempt(String outTradeNo);

    /** Only a committed SENDING claim produces a ticket; aliases cannot produce another. */
    record DispatchTicket(
            String canonicalOperationNo,
            String paymentNo,
            String outTradeNo,
            long revision,
            SettlePort.PaymentOpen payment) {}

    /**
     * These causes are known before any channel client call, never inferred from a failed response.
     */
    enum NotSentReason {
        NO_GATEWAY,
        LOCAL_PRECONDITION
    }
}

package ai.neargo.shop.spi.settle;

import ai.neargo.shop.spi.media.ReceiptEvidencePort.ReceiptEvidence;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Trusted trade-owner instructions with durable original-number recovery; not a public cash API.
 */
public interface PaymentOperationPort {
    /** Freeze an instruction before any ledger allocation or external dispatch. */
    PaymentOperationView prepare(PaymentOperationReq request);

    /**
     * Dispatch only the original canonical instruction; the trade owner must authorize current C.
     */
    PaymentOperationView initiate(String operationNo);

    /** Observe the original number only; never dispatch or allocate a replacement. */
    PaymentOperationView query(String operationNo);

    /**
     * Record a currently authorized merchant's full external receipt on the real payment ledger.
     */
    PaymentOperationView confirmOffline(OfflinePaymentReceiptReq request);

    /** Frozen order-owner facts, never deserialized directly from a public request. */
    record PaymentOperationReq(
            String tenantNo,
            String operationNo,
            String requestHash,
            String orderNo,
            String buyerNo,
            String entityNo,
            String storeNo,
            SettlePort.PaymentOpen payment) {
        /** Reject inconsistent frozen owner instructions before creating financial state. */
        public PaymentOperationReq {
            if (!"MAIN".equals(tenantNo)) {
                throw invalid();
            }
            reference(operationNo, 64);
            reference(orderNo, 64);
            reference(buyerNo, 64);
            reference(entityNo, 32);
            reference(storeNo, 32);
            if (requestHash == null || !requestHash.matches("[0-9a-f]{64}")) {
                throw invalid();
            }
            if (payment == null
                    || !orderNo.equals(payment.orderNo())
                    || !buyerNo.equals(payment.userNo())
                    || !entityNo.equals(payment.entityNo())
                    || payment.amountMinor() < 0
                    || !"CNY".equals(payment.currency())) {
                throw invalid();
            }
            reference(payment.payChannel(), 32);
            if ("OFFLINE".equals(payment.payChannel()) || "FREE".equals(payment.payChannel())) {
                if (!payment.stores().isEmpty()
                        || "FREE".equals(payment.payChannel()) && payment.amountMinor() != 0) {
                    throw invalid();
                }
            } else {
                if (payment.stores().size() != 1) {
                    throw invalid();
                }
                var store = payment.stores().getFirst();
                var route = store.route();
                reference(store.subOrderNo(), 64);
                if (route == null
                        || !entityNo.equals(route.merchantNo())
                        || !storeNo.equals(route.storeNo())
                        || !payment.payChannel().equals(route.channel())
                        || !("DIRECT".equals(route.fundsPath())
                                || "AGGREGATED".equals(route.fundsPath()))) {
                    throw invalid();
                }
                reference(route.payMerchantNo(), 64);
                reference(route.subMerchantId(), 64);
                reference(route.accountVersion(), 128);
            }
        }

        @Override
        public String toString() {
            return "PaymentOperationReq[" + operationNo + "]";
        }
    }

    /**
     * A process observation; the receipt amount and status still belong exclusively to stl_payment.
     */
    record PaymentOperationView(
            String operationNo,
            String canonicalOperationNo,
            String phase,
            String paymentNo,
            String outTradeNo,
            String requestHash,
            String receiptNo,
            String reasonCode,
            SettlePort.PaymentReceipt receipt,
            Map<String, String> payParams,
            Instant payParamsExpiresAt,
            Instant observedAt) {
        /** Copy reusable parameters so observations cannot mutate persisted instruction results. */
        public PaymentOperationView {
            payParams = payParams == null ? Map.of() : Map.copyOf(payParams);
        }

        @Override
        public String toString() {
            return "PaymentOperationView[" + operationNo + "," + phase + "]";
        }
    }

    /** Actual cashier, original full amount and immutable private attachment facts. */
    record OfflinePaymentReceiptReq(
            PaymentOperationReq operation,
            String method,
            String evidenceRef,
            String actorNo,
            String actorRealm,
            String actorKind,
            String actorAccountNo,
            Instant receivedAt,
            String note,
            ReceiptEvidence evidence) {
        /** Require complete actual-account receipt facts before entering the financial owner. */
        public OfflinePaymentReceiptReq {
            Objects.requireNonNull(operation);
            if (!"OFFLINE".equals(operation.payment().payChannel())
                    || !operation.payment().stores().isEmpty()
                    || !("CASH".equals(method) || "BANK_TRANSFER".equals(method))
                    || !"MERCHANT".equals(actorRealm)
                    || !("MCH".equals(actorKind) || "USR".equals(actorKind))
                    || receivedAt == null
                    || !receivedAt.equals(Instant.ofEpochMilli(receivedAt.toEpochMilli()))) {
                throw invalid();
            }
            reference(actorNo, 64);
            reference(actorAccountNo, 64);
            if (note != null
                    && (note.isBlank() || note.length() > 500 || !note.equals(note.strip()))) {
                throw invalid();
            }
            if ("CASH".equals(method) && note == null) {
                throw invalid();
            }
            if ("BANK_TRANSFER".equals(method) && evidenceRef == null) {
                throw invalid();
            }
            if ((evidenceRef == null) != (evidence == null)) {
                throw invalid();
            }
            if (evidence != null) {
                reference(evidenceRef, 255);
                if (!evidenceRef.equals(evidence.assetKey())
                        || !"MAIN".equals(evidence.tenantNo())
                        || !operation.entityNo().equals(evidence.entityNo())
                        || !operation.storeNo().equals(evidence.storeNo())
                        || !actorAccountNo.equals(evidence.uploadedByAccountNo())
                        || !"OFFLINE_RECEIPT".equals(evidence.bizType())
                        || evidence.bytes() <= 0
                        || evidence.uploadedAt() == null
                        || evidence.factHash() == null
                        || !evidence.factHash().matches("[0-9a-f]{64}")) {
                    throw invalid();
                }
            }
        }

        @Override
        public String toString() {
            return "OfflinePaymentReceiptReq[redacted]";
        }
    }

    private static void reference(String value, int max) {
        if (value == null
                || value.isBlank()
                || value.length() > max
                || !value.equals(value.strip())) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid frozen payment instruction");
    }
}

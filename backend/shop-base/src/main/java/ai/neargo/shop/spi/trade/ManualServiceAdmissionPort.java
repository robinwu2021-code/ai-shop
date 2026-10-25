package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutQuote;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.trade.CheckoutPort.OperationRef;
import ai.neargo.shop.spi.trade.CheckoutPort.PrepareCheckoutReq;
import ai.neargo.shop.spi.trade.CheckoutPort.SourceLookup;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;

/** Merchant declarations remain separate from Pay receipts and never authorize service start. */
public interface ManualServiceAdmissionPort {
    /** Freeze ordinary shared pricing after actual current merchant authorization. */
    CheckoutQuote prepare(CheckoutScope scope, PrepareCheckoutReq request);

    /** Record one authorized declaration and inspect the original stock owner outcome. */
    ManualAdmissionView confirm(CheckoutScope scope, ConfirmManualReceipt request);

    /** Recover the original declaration under current actor/store authorization. */
    ManualAdmissionView query(CheckoutScope scope, ManualLookup request);

    /** Observe the same checkout line identities without creating financial evidence. */
    ManualSourceFacts source(CheckoutScope scope, SourceLookup request);

    enum ManualMethod {
        CASH,
        OFFLINE_TRANSFER
    }

    /** Positive exact money is a merchant assertion; it is never bank verification. */
    record ConfirmManualReceipt(
            OperationRef operation,
            String requestKey,
            long expectedOrderRevision,
            ManualMethod method,
            long declaredMinor,
            String currency,
            Instant receivedAt,
            String note) {
        public ConfirmManualReceipt {
            if (operation == null
                    || requestKey == null
                    || requestKey.isBlank()
                    || requestKey.length() > 128
                    || expectedOrderRevision < 0
                    || method == null
                    || declaredMinor <= 0
                    || !"CNY".equals(currency)
                    || receivedAt == null
                    || receivedAt.getNano() % 1_000_000 != 0
                    || note != null && note.length() > 500) {
                throw new IllegalArgumentException("invalid manual receipt");
            }
        }

        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw new IllegalArgumentException("unknown manual receipt field");
        }

        @Override
        public String toString() {
            return "ConfirmManualReceipt[REDACTED]";
        }
    }

    /** Exactly one original identity prevents ambiguous recovery and cross-actor key lookup. */
    record ManualLookup(OperationRef operation, String declarationNo, String requestKey) {
        public ManualLookup {
            if (operation == null
                    || (declarationNo == null) == (requestKey == null)
                    || declarationNo != null
                            && (declarationNo.isBlank() || declarationNo.length() > 64)
                    || requestKey != null && (requestKey.isBlank() || requestKey.length() > 128)) {
                throw new IllegalArgumentException("invalid manual lookup");
            }
        }

        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw new IllegalArgumentException("unknown manual lookup field");
        }

        @Override
        public String toString() {
            return "ManualLookup[REDACTED]";
        }
    }

    /** Restricted cashier/order-view facts; no Pay identifiers or raw notes. */
    record ManualAdmissionView(
            OperationRef operation,
            String orderNo,
            String subOrderNo,
            long orderRevision,
            long admissionRevision,
            String admissionState,
            String receiptProvenance,
            String manualDeclarationNo,
            ManualMethod method,
            Long declaredMinor,
            String currency,
            Instant receivedAt,
            Instant recordedAt,
            String operationState,
            String reasonCode) {
        public ManualAdmissionView {
            if (operation == null
                    || orderRevision < 0
                    || admissionRevision < 0
                    || !state(admissionState)
                    || !"CNY".equals(currency)
                    || !optionalReference(orderNo)
                    || !optionalReference(subOrderNo)
                    || (orderNo == null) != (subOrderNo == null)) {
                throw invalidResponse();
            }
            if ("AWAITING_RECEIPT".equals(admissionState)) {
                if (admissionRevision != 0
                        || receiptProvenance != null
                        || manualDeclarationNo != null
                        || method != null
                        || declaredMinor != null
                        || receivedAt != null
                        || recordedAt != null
                        || !"AWAITING_RECEIPT".equals(operationState)) {
                    throw invalidResponse();
                }
            } else {
                if (!reference(orderNo)
                        || !reference(subOrderNo)
                        || admissionRevision <= 0
                        || orderRevision <= 0
                        || !"MANUAL_DECLARATION".equals(receiptProvenance)
                        || !reference(manualDeclarationNo)
                        || method == null
                        || declaredMinor == null
                        || declaredMinor <= 0
                        || !time(receivedAt)
                        || !time(recordedAt)
                        || receivedAt.isAfter(recordedAt)
                        || !("UNKNOWN".equals(admissionState) ? "STOCK_PENDING" : admissionState)
                                .equals(operationState)) {
                    throw invalidResponse();
                }
            }
            if (reasonCode != null && !reference(reasonCode)) {
                throw invalidResponse();
            }
        }
    }

    /** M1 accepts only the actual identified account; the genuine guest lane follows in M2. */
    record CustomerParty(String kind, String userNo, String guestNo, long identityRevision) {
        public CustomerParty {
            if (!"USER".equals(kind)
                    || userNo == null
                    || userNo.isBlank()
                    || guestNo != null
                    || userNo.length() > 64
                    || !userNo.equals(userNo.strip())
                    || identityRevision <= 0) {
                throw new IllegalArgumentException("unsupported manual customer party");
            }
        }
    }

    /** A canonical manual observation is independent from the old funded hash domain. */
    record ManualSourceFacts(
            OperationRef operation,
            String orderNo,
            String subOrderNo,
            CustomerParty party,
            String entityNo,
            String storeNo,
            String admissionKind,
            long manualObservationRevision,
            String manualObservationHash,
            long orderRevision,
            long admissionRevision,
            String admissionState,
            List<ManualSourceLine> lines,
            Instant observedAt) {
        public ManualSourceFacts {
            if (operation == null
                    || party == null
                    || !reference(entityNo)
                    || !reference(storeNo)
                    || !"MANUAL_OFFLINE".equals(admissionKind)
                    || manualObservationRevision < 0
                    || orderRevision < 0
                    || admissionRevision < 0
                    || !state(admissionState)
                    || !time(observedAt)
                    || lines == null
                    || (manualObservationRevision == 0) != (manualObservationHash == null)
                    || manualObservationHash != null
                            && !manualObservationHash.matches("[a-f0-9]{64}")
                    || !optionalReference(orderNo)
                    || !optionalReference(subOrderNo)
                    || (orderNo == null) != (subOrderNo == null)) {
                throw invalidResponse();
            }
            lines = List.copyOf(lines);
            if ("ADMITTED".equals(admissionState)) {
                if (!reference(orderNo)
                        || !reference(subOrderNo)
                        || manualObservationRevision <= 0
                        || orderRevision <= 0
                        || admissionRevision <= 0
                        || lines.isEmpty()
                        || lines.size() > 100) {
                    throw invalidResponse();
                }
            } else if (!lines.isEmpty()) {
                throw invalidResponse();
            }
            var ids = new HashSet<String>();
            var clients = new HashSet<String>();
            for (var line : lines) {
                if (!ids.add(line.sourceLineNo()) || !clients.add(line.clientLineRef())) {
                    throw invalidResponse();
                }
            }
        }
    }

    /** Stable common checkout line identity; availability grants no permission to start. */
    record ManualSourceLine(
            String sourceLineNo,
            String clientLineRef,
            String goodsNo,
            String skuNo,
            int orderedQuantity,
            int availableQuantity,
            String uom) {
        public ManualSourceLine {
            if (!reference(sourceLineNo)
                    || !reference(clientLineRef)
                    || !reference(goodsNo)
                    || !reference(skuNo)
                    || orderedQuantity <= 0
                    || availableQuantity < 0
                    || availableQuantity > orderedQuantity
                    || !"COUNT".equals(uom)) {
                throw invalidResponse();
            }
        }
    }

    private static boolean state(String value) {
        return value != null
                && List.of("AWAITING_RECEIPT", "ADMITTED", "SUSPENDED", "CANCELLED", "UNKNOWN")
                        .contains(value);
    }

    private static boolean reference(String value) {
        return value != null
                && !value.isBlank()
                && value.length() <= 64
                && value.equals(value.strip());
    }

    private static boolean optionalReference(String value) {
        return value == null || reference(value);
    }

    private static boolean time(Instant value) {
        return value != null;
    }

    private static IllegalArgumentException invalidResponse() {
        return new IllegalArgumentException("inconsistent manual response");
    }
}

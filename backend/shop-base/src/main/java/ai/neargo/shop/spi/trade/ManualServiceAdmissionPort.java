package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutLineReq;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutQuote;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutResult;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.trade.CheckoutPort.OperationRef;
import ai.neargo.shop.spi.trade.CheckoutPort.PrepareCheckoutReq;
import ai.neargo.shop.spi.trade.CheckoutPort.SourceLookup;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    /** Identify a real counter customer and freeze shared ordinary economics without C login. */
    default CounterCheckoutQuote prepareCounter(CheckoutScope scope, PrepareCounterReq request) {
        throw new UnsupportedOperationException("counter purchase unavailable");
    }

    /** Read the original merchant quote without reinterpreting a claimed historical guest. */
    default CounterCheckoutQuote readCounterQuote(CheckoutScope scope, OperationRef operation) {
        throw new UnsupportedOperationException("counter purchase unavailable");
    }

    /** Explicit in-person purchase acceptance is separate from registration and receipt. */
    default CounterConfirmation acceptCounter(CheckoutScope scope, AcceptCounterReq request) {
        throw new UnsupportedOperationException("counter purchase unavailable");
    }

    /** Place the accepted original under current cashier authority. */
    default CheckoutResult placeCounter(CheckoutScope scope, PlaceCounterReq request) {
        throw new UnsupportedOperationException("counter purchase unavailable");
    }

    /**
     * Trusted HOST composition derives member purposes only from an authenticated full counter
     * request.
     */
    interface CounterCustomerResolver {
        /** Resolve an authenticated complete request through the actual member-owner purposes. */
        CounterCustomerResolution resolveBound(CheckoutScope scope, PrepareCounterReq request);
    }

    /**
     * Selector revision belongs to claimed identity evidence, never to the resulting USER party.
     */
    record CounterCustomerResolution(CustomerParty party, Long selectorRevision) {
        public CounterCustomerResolution {
            if (party == null
                    || !"USER".equals(party.kind())
                    || party.identityRevision() != null
                    || selectorRevision != null && selectorRevision <= 0) {
                throw new IllegalArgumentException("invalid bound counter customer");
            }
        }
    }

    /** A selector carries no permission and its reusable token is always redacted. */
    record CounterCustomerRef(String kind, String memberNo, String guestNo, String claimToken) {
        public CounterCustomerRef {
            if (!("USER".equals(kind)
                            && bounded(memberNo, 64)
                            && guestNo == null
                            && claimToken == null
                    || ("GUEST".equals(kind) || "CLAIMED_GUEST".equals(kind))
                            && memberNo == null
                            && bounded(guestNo, 32)
                            && claimToken != null
                            && claimToken.matches("[A-Za-z0-9_-]{43}"))) {
                throw new IllegalArgumentException("invalid counter customer selector");
            }
        }

        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalidResponse();
        }

        @Override
        public String toString() {
            return "CounterCustomerRef[REDACTED]";
        }
    }

    /** No caller supplied mode, amount or account can change the shared counter economics. */
    record PrepareCounterReq(
            OperationRef operation,
            String requestKey,
            CounterCustomerRef customer,
            long scopeRevision,
            String scopeHash,
            List<CheckoutLineReq> lines,
            String couponNo,
            Map<String, String> activityChoices,
            String remark) {
        public PrepareCounterReq {
            if (operation == null || customer == null) {
                throw invalidResponse();
            }
            // Reuse the exact legacy selection limits; this transient sentinel is never a proof or
            // persisted.
            var shape =
                    new PrepareCheckoutReq(
                            operation,
                            requestKey,
                            "COUNTER_SHAPE",
                            scopeRevision,
                            scopeHash,
                            lines,
                            "OFFLINE",
                            couponNo,
                            activityChoices,
                            remark);
            lines = shape.lines();
            activityChoices = shape.activityChoices();
        }

        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalidResponse();
        }

        @Override
        public String toString() {
            return "PrepareCounterReq[REDACTED]";
        }
    }

    /** Typed party and shared quote must agree exactly before they may be displayed together. */
    record CounterCheckoutQuote(
            CheckoutQuote quote, CustomerParty party, String purchaseAuthority) {
        public CounterCheckoutQuote {
            if (quote == null
                    || party == null
                    || !"MERCHANT_RECORDED_IN_PERSON".equals(purchaseAuthority)
                    || !Objects.equals(quote.buyerNo(), party.userNo())
                    || !"OFFLINE".equals(quote.payMode())
                    || !"STORE_VERIFY".equals(quote.fulfillment())
                    || !"SERVICE_EXECUTION".equals(quote.fulfillmentAuthority())
                    || !"CNY".equals(quote.currency())
                    || quote.dueMinor() <= 0
                    || quote.goodsMinor() <= 0
                    || quote.discountMinor() < 0
                    || !reference(quote.entityNo())
                    || !reference(quote.storeNo())
                    || quote.lines().isEmpty()
                    || quote.lines().size() > 20
                    || quote.expiresAt() == null) {
                throw invalidResponse();
            }
            long goods = 0, discounts = 0, due = 0;
            var refs = new HashSet<String>();
            var skus = new HashSet<String>();
            try {
                for (var line : quote.lines()) {
                    if (line.quantity() <= 0
                            || !"COUNT".equals(line.uom())
                            || line.unitPriceMinor() <= 0
                            || Math.multiplyExact(line.unitPriceMinor(), line.quantity())
                                    != line.grossMinor()
                            || line.discountMinor() < 0
                            || line.dueMinor() < 0
                            || Math.subtractExact(line.grossMinor(), line.discountMinor())
                                    != line.dueMinor()
                            || !refs.add(line.clientLineRef())
                            || !skus.add(line.skuNo())) {
                        throw invalidResponse();
                    }
                    goods = Math.addExact(goods, line.grossMinor());
                    discounts = Math.addExact(discounts, line.discountMinor());
                    due = Math.addExact(due, line.dueMinor());
                }
            } catch (ArithmeticException overflow) {
                throw invalidResponse();
            }
            if (goods != quote.goodsMinor()
                    || discounts != quote.discountMinor()
                    || due != quote.dueMinor()) {
                throw invalidResponse();
            }
        }

        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalidResponse();
        }

        @Override
        public String toString() {
            return "CounterCheckoutQuote[REDACTED]";
        }
    }

    /** In-person acceptance references exactly the quote displayed, never an override amount. */
    record AcceptCounterReq(
            OperationRef operation,
            String requestKey,
            String quoteNo,
            long quoteRevision,
            String quoteHash,
            Long identityRevision,
            Instant acceptedAt,
            String evidenceKind) {
        public AcceptCounterReq {
            if (operation == null
                    || !bounded(requestKey, 128)
                    || !reference(quoteNo)
                    || quoteRevision != 1
                    || quoteHash == null
                    || !quoteHash.matches("[a-f0-9]{64}")
                    || identityRevision != null && identityRevision <= 0
                    || acceptedAt == null
                    || acceptedAt.getNano() % 1_000_000 != 0
                    || !"MERCHANT_RECORDED_IN_PERSON".equals(evidenceKind)) {
                throw invalidResponse();
            }
        }

        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalidResponse();
        }

        @Override
        public String toString() {
            return "AcceptCounterReq[REDACTED]";
        }
    }

    /** Immutable owner acceptance; current identity linkage never rewrites its historical party. */
    record CounterConfirmation(
            OperationRef operation,
            String confirmationNo,
            String quoteNo,
            long quoteRevision,
            String quoteHash,
            CustomerParty party,
            String purchaseAuthority,
            Instant acceptedAt,
            Instant expiresAt,
            String status) {
        public CounterConfirmation {
            if (operation == null
                    || !reference(confirmationNo)
                    || !reference(quoteNo)
                    || quoteRevision != 1
                    || quoteHash == null
                    || !quoteHash.matches("[a-f0-9]{64}")
                    || party == null
                    || !"MERCHANT_RECORDED_IN_PERSON".equals(purchaseAuthority)
                    || acceptedAt == null
                    || acceptedAt.getNano() % 1_000_000 != 0
                    || expiresAt == null
                    || !acceptedAt.isBefore(expiresAt)
                    || !List.of("AUTHORIZED", "CONSUMED", "REVOKED", "EXPIRED").contains(status)) {
                throw invalidResponse();
            }
        }

        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalidResponse();
        }

        @Override
        public String toString() {
            return "CounterConfirmation[REDACTED]";
        }
    }

    /** Exact original acceptance and key are required for every live placement retry. */
    record PlaceCounterReq(OperationRef operation, String confirmationNo, String requestKey) {
        public PlaceCounterReq {
            if (operation == null || !reference(confirmationNo) || !bounded(requestKey, 128)) {
                throw invalidResponse();
            }
        }

        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalidResponse();
        }

        @Override
        public String toString() {
            return "PlaceCounterReq[REDACTED]";
        }
    }

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

    /** Exactly one actual customer; nullable USER revision does not fabricate an owner version. */
    record CustomerParty(String kind, String userNo, String guestNo, Long identityRevision) {
        public CustomerParty {
            if (!("USER".equals(kind)
                            && bounded(userNo, 64)
                            && guestNo == null
                            && (identityRevision == null || identityRevision > 0)
                    || "GUEST".equals(kind)
                            && userNo == null
                            && bounded(guestNo, 32)
                            && identityRevision != null
                            && identityRevision > 0)) {
                throw new IllegalArgumentException("unsupported manual customer party");
            }
        }

        /** Preserve the original M1 primitive constructor and serialized numeric revision. */
        public CustomerParty(String kind, String userNo, String guestNo, long identityRevision) {
            this(kind, userNo, guestNo, Long.valueOf(identityRevision));
        }

        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw invalidResponse();
        }

        @Override
        public String toString() {
            return "CustomerParty[REDACTED]";
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
            Instant observedAt,
            Long dueMinor,
            String currency,
            ManualAdmissionView declaration,
            String purchaseAuthority) {
        /**
         * Old providers retain the exact M1 source tuple; missing display facts stay explicitly
         * incomplete.
         */
        public ManualSourceFacts(
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
            this(
                    operation,
                    orderNo,
                    subOrderNo,
                    party,
                    entityNo,
                    storeNo,
                    admissionKind,
                    manualObservationRevision,
                    manualObservationHash,
                    orderRevision,
                    admissionRevision,
                    admissionState,
                    lines,
                    observedAt,
                    null,
                    null,
                    null,
                    null);
        }

        public ManualSourceFacts {
            if (dueMinor != null && dueMinor < 0
                    || currency != null && !currency.matches("[A-Z]{3}")
                    || purchaseAuthority != null
                            && !List.of("CONSUMER_SESSION", "MERCHANT_RECORDED_IN_PERSON")
                                    .contains(purchaseAuthority)) {
                throw invalidResponse();
            }
            if (declaration != null
                    && (operation == null
                            || !operation.equals(declaration.operation())
                            || !Objects.equals(orderNo, declaration.orderNo())
                            || !Objects.equals(subOrderNo, declaration.subOrderNo())
                            || orderRevision != declaration.orderRevision()
                            || admissionRevision != declaration.admissionRevision()
                            || currency != null && !currency.equals(declaration.currency())
                            || declaration.declaredMinor() != null
                                    && dueMinor != null
                                    && !dueMinor.equals(declaration.declaredMinor()))) {
                throw invalidResponse();
            }
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

    private static boolean bounded(String value, int max) {
        return value != null
                && !value.isBlank()
                && value.length() <= max
                && value.equals(value.strip());
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

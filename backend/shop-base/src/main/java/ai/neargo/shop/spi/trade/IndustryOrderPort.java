package ai.neargo.shop.spi.trade;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Credentials;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

/** Source-authorized formal order creation; no client price or debit authorization is accepted. */
public interface IndustryOrderPort {
    enum Mode {
        PREPAY,
        POSTPAY
    }

    record Identity(
            String tenantNo,
            String entityNo,
            String storeNo,
            String buyerNo,
            String actorNo,
            @JsonInclude(JsonInclude.Include.NON_NULL) String partyNo) {
        public Identity {
            if (partyNo != null && buyerNo == null) buyerNo = "";
        }

        public Identity(
                String tenantNo, String entityNo, String storeNo, String buyerNo, String actorNo) {
            this(tenantNo, entityNo, storeNo, buyerNo, actorNo, null);
        }
    }

    record Command(
            Identity identity,
            PricingSourceRef source,
            Credentials credentials,
            String quoteNo,
            String confirmationDigest,
            Mode mode,
            String payScene) {}

    /**
     * qty counts billing units; saleQuantity is the exact physical quantity and never inferred from
     * qty.
     */
    record Allocation(
            String sourceLineNo,
            String componentRef,
            long orderItemId,
            int qty,
            long amountMinor,
            String currency,
            String hash,
            BigDecimal saleQuantity,
            String quantityUnit) {
        public Allocation {
            saleQuantity = saleQuantity == null ? BigDecimal.valueOf(qty) : saleQuantity;
            quantityUnit = quantityUnit == null ? "EA" : quantityUnit;
        }

        public Allocation(
                String line,
                String component,
                long item,
                int qty,
                long amount,
                String currency,
                String hash) {
            this(line, component, item, qty, amount, currency, hash, BigDecimal.valueOf(qty), "EA");
        }
    }

    /**
     * PREPARED has original document identities, null money/currency and no committed item
     * allocations.
     */
    record Result(
            String orderNo,
            String subOrderNo,
            String quoteNo,
            String status,
            String currency,
            Long payAmountMinor,
            long sourceVersion,
            int inventoryLifecycleVersion,
            List<Allocation> allocations,
            String orderMode,
            String fulfillmentStatus,
            boolean acceptedUnpaid,
            Plan inventoryPlan,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long effectiveReceivableMinor,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long receivableAdjustmentMinor,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<Allocation> effectiveAllocations,
            @JsonInclude(JsonInclude.Include.NON_NULL) String amendmentSourceHash) {
        public Result(
                String order,
                String sub,
                String quote,
                String status,
                String currency,
                Long amount,
                long version,
                int lifecycle,
                List<Allocation> allocations,
                String mode,
                String fulfillment,
                boolean accepted,
                Plan plan,
                Long effective,
                Long adjustment) {
            this(
                    order,
                    sub,
                    quote,
                    status,
                    currency,
                    amount,
                    version,
                    lifecycle,
                    allocations,
                    mode,
                    fulfillment,
                    accepted,
                    plan,
                    effective,
                    adjustment,
                    null,
                    null);
        }

        public List<Allocation> actualAllocations() {
            return effectiveAllocations == null ? allocations : effectiveAllocations;
        }

        public Result(
                String order,
                String sub,
                String quote,
                String status,
                String currency,
                Long amount,
                long version,
                int lifecycle,
                List<Allocation> allocations,
                String mode,
                String fulfillment,
                boolean accepted,
                Plan plan) {
            this(
                    order,
                    sub,
                    quote,
                    status,
                    currency,
                    amount,
                    version,
                    lifecycle,
                    allocations,
                    mode,
                    fulfillment,
                    accepted,
                    plan,
                    null,
                    null);
        }

        public Long actualReceivableMinor() {
            return effectiveReceivableMinor == null ? payAmountMinor : effectiveReceivableMinor;
        }

        public Result(
                String orderNo,
                String subOrderNo,
                String quoteNo,
                String status,
                String currency,
                Long amount,
                long version,
                int lifecycle,
                List<Allocation> allocations,
                String mode,
                String fulfillment,
                boolean accepted) {
            this(
                    orderNo,
                    subOrderNo,
                    quoteNo,
                    status,
                    currency,
                    amount,
                    version,
                    lifecycle,
                    allocations,
                    mode,
                    fulfillment,
                    accepted,
                    null);
        }

        public Result {
            allocations = List.copyOf(allocations);
            if (effectiveAllocations != null)
                effectiveAllocations = List.copyOf(effectiveAllocations);
        }

        /**
         * Compatibility for existing PREPAY adapters; absence of proof never authorizes unpaid
         * acceptance.
         */
        public Result(
                String orderNo,
                String subOrderNo,
                String quoteNo,
                String status,
                String currency,
                Long amount,
                long version,
                int lifecycle,
                List<Allocation> allocations) {
            this(
                    orderNo,
                    subOrderNo,
                    quoteNo,
                    status,
                    currency,
                    amount,
                    version,
                    lifecycle,
                    allocations,
                    null,
                    null,
                    false);
        }
    }

    /**
     * Advances original lifecycle-3 fulfillment only after the independent inventory owner confirms
     * unpaid acceptance.
     */
    Result acceptUnpaid(Identity identity, String orderNo);

    /**
     * Trusted current-store gateway only; actor is staff, buyer remains a real customer, and no
     * debit is granted.
     */
    default Result placeForStore(Command command, String key) {
        throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
    }

    /**
     * Explicit registered-buyer staff dine-in capability; existing command fingerprints remain
     * unchanged.
     */
    default Result placeForDiningRoom(Command command, String key) {
        throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
    }

    /**
     * Replays only the persisted staff identity and original key; never creates a replacement
     * intent.
     */
    default Result queryForStore(Identity identity, String key) {
        throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
    }

    /**
     * Explicit source-authorized customer/staff service fulfillment. Never grants payment consent.
     */
    default Result placeForService(Command command, String key, String fulfillment, boolean staff) {
        throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
    }

    Result place(Command command, String key);

    /**
     * Cancels only an uncommitted original staff intent; never cancels accepted service debt or
     * refunds funds.
     */
    default Result cancelForStore(Command command, String key, String fulfillment) {
        throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
    }

    Result cancel(Command command, String key);

    Result query(Identity identity, String key);

    Result get(Identity identity, String orderNo);

    Result getForStore(Scope scope, String actorNo, String orderNo);
}

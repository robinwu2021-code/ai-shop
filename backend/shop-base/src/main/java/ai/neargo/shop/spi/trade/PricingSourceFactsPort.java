package ai.neargo.shop.spi.trade;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.common.pricing.PricingFacts;
import ai.neargo.shop.common.pricing.PricingPortion;
import ai.neargo.shop.common.pricing.PricingUnit;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import java.util.List;

/**
 * Authorized source prices only; these facts do not admit promotions, taxes, funds or order
 * submission.
 */
public interface PricingSourceFactsPort {
    String sourceSystem();

    /**
     * Original source authenticates anonymous/device parties; this grants no account or payment
     * permission.
     */
    default Party party(IndustryOrderPort.Command command) {
        throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
    }

    /**
     * Null means the existing integral finished-SKU contract applies; complex sources must return a
     * real plan.
     */
    default Plan demandPlan(IndustryOrderPort.Command command, String orderNo, String subOrderNo) {
        return null;
    }

    record Party(
            Scope scope,
            String partyNo,
            String kind,
            String displayName,
            String sessionNo,
            String deviceNo,
            String sourceNo,
            long sourceVersion) {}

    record Delivery(
            Scope scope,
            String sourceNo,
            long sourceVersion,
            long revision,
            String receiverName,
            String receiverPhone,
            String address,
            String deliveryInstruction,
            @JsonInclude(Include.NON_NULL) Integer latE6,
            @JsonInclude(Include.NON_NULL) Integer lngE6) {
        public Delivery(
                Scope scope,
                String source,
                long version,
                long revision,
                String name,
                String phone,
                String address,
                String instruction) {
            this(scope, source, version, revision, name, phone, address, instruction, null, null);
        }
    }

    default Delivery delivery(IndustryOrderPort.Command command) {
        throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
    }

    Snapshot staff(Scope scope, PricingSourceRef source);

    Snapshot customer(PricingSourceRef source, Credentials credentials);

    /**
     * Original durable withdrawal authority, distinct from current saleability and not a placement
     * grant.
     */
    default Snapshot cancellationForStore(
            IndustryOrderPort.Command command, String key, String fulfillment) {
        throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
    }

    /** Server-resolved staff scope; constructing this value grants no merchant or store access. */
    record Scope(String tenantNo, String entityNo, String storeNo) {
        public Scope {
            PricingSourceFacts.key(tenantNo);
            PricingSourceFacts.key(entityNo);
            PricingSourceFacts.key(storeNo);
            if (!"MAIN".equals(tenantNo))
                throw new IllegalArgumentException("Existing MAIN tenant required");
        }
    }

    /** Passed to the source authority on every read; never serialized into a pricing snapshot. */
    record Credentials(String sessionNo, String accessToken, String buyerNo) {
        public Credentials {
            if (sessionNo != null) PricingSourceFacts.key(sessionNo);
            if (buyerNo != null) PricingSourceFacts.key(buyerNo);
            if (accessToken != null && (accessToken.isBlank() || accessToken.length() > 512)) {
                throw new IllegalArgumentException("Bounded source credentials required");
            }
            if (accessToken == null && buyerNo == null)
                throw new IllegalArgumentException("Source identity required");
        }

        @Override
        public String toString() {
            return "Credentials[REDACTED]";
        }
    }

    enum Kind {
        MAIN,
        ADDON,
        BUNDLE_INCLUDED,
        BUNDLE_ADD,
        BUNDLE_DIFFERENCE
    }

    record Component(Kind kind, PricingUnit unit) {}

    record InvoiceBasis(String invoiceLineRef, List<PricingPortion> portions, MoneyMinor amount) {
        public InvoiceBasis {
            portions = portions == null ? null : List.copyOf(portions);
        }
    }

    record Snapshot(
            int schemaVersion,
            Scope scope,
            String subjectNo,
            PricingSourceRef source,
            String channel,
            PricingFacts prices,
            List<Component> composition,
            List<InvoiceBasis> invoiceBases,
            String snapshotHash,
            @JsonInclude(Include.NON_NULL) List<PricingFeeFacts.Source> fees) {
        public Snapshot(
                int schemaVersion,
                Scope scope,
                String subjectNo,
                PricingSourceRef source,
                String channel,
                PricingFacts prices,
                List<Component> composition,
                List<InvoiceBasis> invoiceBases,
                String snapshotHash) {
            this(
                    schemaVersion,
                    scope,
                    subjectNo,
                    source,
                    channel,
                    prices,
                    composition,
                    invoiceBases,
                    snapshotHash,
                    null);
        }

        public Snapshot {
            composition = composition == null ? null : List.copyOf(composition);
            invoiceBases = invoiceBases == null ? null : List.copyOf(invoiceBases);
            fees = fees == null || fees.isEmpty() ? null : List.copyOf(fees);
        }
    }
}

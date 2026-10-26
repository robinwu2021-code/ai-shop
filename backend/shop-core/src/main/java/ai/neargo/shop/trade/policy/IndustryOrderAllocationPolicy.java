package ai.neargo.shop.trade.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.common.pricing.PricingUnit;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Allocation;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Reads original immutable allocation documents, without treating their evidence as allocations.
 */
public final class IndustryOrderAllocationPolicy {
    private IndustryOrderAllocationPolicy() {}

    public static List<Allocation> read(ObjectMapper json, String snapshot) {
        var document = json.readTree(snapshot);
        // Current order creation stores evidence alongside allocations; retain historical arrays.
        var values = document.isArray() ? document : document.get("allocations");
        if (values == null || !values.isArray()) throw BizException.of(ErrorCode.CONFLICT);
        return json.convertValue(values, new TypeReference<>() {});
    }

    /** A missing quote line is valid only for an original, digest-verified included component. */
    public static void requireIncludedZero(
            ObjectMapper json, String snapshot, String orderNo, Allocation allocation) {
        var document = json.readTree(snapshot);
        var evidence = document.get("evidence");
        if (allocation.amountMinor() != 0 || evidence == null || !evidence.isArray())
            throw BizException.of(ErrorCode.CONFLICT);
        int matched = 0;
        for (var entry : evidence) {
            var frozen = json.treeToValue(entry.get("allocation"), Allocation.class);
            if (frozen.orderItemId() != allocation.orderItemId()) continue;
            matched++;
            var source = json.treeToValue(entry.get("source"), PricingUnit.class);
            var price = Map.of("kind", "BUNDLE_INCLUDED", "receivableMinor", 0);
            if (!PricingSnapshotHash.of(frozen).equals(PricingSnapshotHash.of(allocation))
                    || !PricingSnapshotHash.of(entry.get("price"))
                            .equals(PricingSnapshotHash.of(price))
                    || !allocation.sourceLineNo().equals(source.sourceLineNo())
                    || !allocation.componentRef().equals(source.componentRef())
                    || allocation.saleQuantity().compareTo(source.quantity()) != 0
                    || !allocation.currency().equals(source.baseAmount().currency())
                    || source.baseAmount().minor() != 0
                    || source.unitPrice().minor() != 0
                    || !allocation
                            .hash()
                            .equals(
                                    PricingSnapshotHash.of(
                                            List.of(
                                                    "INDUSTRY_ITEM_ALLOCATION_V1",
                                                    orderNo,
                                                    allocation.orderItemId(),
                                                    source,
                                                    price))))
                throw BizException.of(ErrorCode.CONFLICT);
        }
        if (matched != 1) throw BizException.of(ErrorCode.CONFLICT);
    }
}

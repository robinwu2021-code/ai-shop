package ai.neargo.shop.retail.commerce.dto;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;

import java.util.HashSet;
import java.util.List;

/**
 * Immutable references into original product publications; no copied product, price, tax or media
 * payload.
 */
public record RetailSelection(
        String entityNo,
        String storeNo,
        String selectionNo,
        String revisionNo,
        String connectionNo,
        String connectionRevisionNo,
        List<Entry> entries) {
    public RetailSelection {
        entries = entries == null ? null : List.copyOf(entries);
    }

    public record Entry(
            String goodsNo, int goodsRevision, String goodsContentHash, List<String> skuNos) {
        public Entry {
            skuNos = skuNos == null ? null : List.copyOf(skuNos);
        }
    }

    public String contentHash() {
        validate();
        return PricingSnapshotHash.of(this);
    }

    public void validate() {
        key(entityNo);
        key(storeNo);
        key(selectionNo);
        key(revisionNo);
        key(connectionNo);
        key(connectionRevisionNo);
        if (entries == null || entries.isEmpty() || entries.size() > 100)
            throw new IllegalArgumentException("Invalid selection");
        var goods = new HashSet<String>();
        var skus = new HashSet<String>();
        for (var e : entries) {
            if (e == null) throw new IllegalArgumentException("Missing original reference");
            key(e.goodsNo());
            if (!goods.add(e.goodsNo())
                    || e.goodsRevision() <= 0
                    || e.goodsContentHash() == null
                    || !e.goodsContentHash().matches("[a-f0-9]{64}")
                    || e.skuNos() == null
                    || e.skuNos().isEmpty())
                throw new IllegalArgumentException("Invalid original reference");
            for (var sku : e.skuNos()) {
                key(sku);
                if (!skus.add(sku)) throw new IllegalArgumentException("Duplicate SKU");
            }
        }
        if (skus.size() > 100)
            throw new IllegalArgumentException("Selection exceeds original price batch");
    }

    public static void key(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,32}"))
            throw new IllegalArgumentException("Invalid identity");
    }
}

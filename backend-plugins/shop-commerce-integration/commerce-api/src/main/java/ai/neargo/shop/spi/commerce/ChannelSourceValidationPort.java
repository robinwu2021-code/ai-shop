package ai.neargo.shop.spi.commerce;

import java.time.Instant;

/** 由原资源owner证明冻结来源，不提供默认通过实现。 */
public interface ChannelSourceValidationPort {
    Validation validate(Source source);

    enum Owner {
        FOOD,
        RETAIL
    }

    enum Kind {
        MENU,
        SELECTION,
        GOODS,
        SKU,
        OPTION,
        WAREHOUSE,
        INVENTORY_POLICY
    }

    enum Status {
        VALID,
        STALE,
        UNSUPPORTED,
        UNKNOWN
    }

    record Source(
            String entityNo,
            String storeNo,
            Owner owner,
            Kind kind,
            String sourceRef,
            String sourceRevision) {}

    record Validation(
            Status status,
            String canonicalRef,
            String canonicalRevision,
            String contentHash,
            Instant validUntil,
            String ownerReceiptNo) {}
}

package ai.neargo.shop.spi.commerce;

import java.time.Instant;

/** 由原资源owner证明冻结来源，不提供默认通过实现。salesOfferRef非空时须同时证明资源属于该销售条目；旧六参数回执不证明此关系。 */
public interface ChannelSourceValidationPort {
    Validation validate(Source source);

    enum Owner {
        FOOD,
        RETAIL
    }

    enum Kind {
        MENU,
        CATEGORY,
        OFFER,
        OPTION_GROUP,
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
            String sourceRevision,
            String salesOfferRef) {
        public Source(
                String entityNo,
                String storeNo,
                Owner owner,
                Kind kind,
                String sourceRef,
                String sourceRevision) {
            this(entityNo, storeNo, owner, kind, sourceRef, sourceRevision, null);
        }
    }

    record Validation(
            Status status,
            String canonicalRef,
            String canonicalRevision,
            String contentHash,
            Instant validUntil,
            String ownerReceiptNo,
            String canonicalSalesOfferRef) {
        public Validation(
                Status status,
                String canonicalRef,
                String canonicalRevision,
                String contentHash,
                Instant validUntil,
                String ownerReceiptNo) {
            this(
                    status,
                    canonicalRef,
                    canonicalRevision,
                    contentHash,
                    validUntil,
                    ownerReceiptNo,
                    null);
        }
    }
}

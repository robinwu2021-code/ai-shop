package ai.neargo.shop.spi.commerce;

import ai.neargo.shop.commerce.dto.CommerceInstantDeserializer;
import ai.neargo.shop.commerce.dto.CommerceTextDeserializer;
import ai.neargo.shop.commerce.dto.CommerceWireCodecs;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;

/** 由原资源owner证明冻结来源，不提供默认通过实现。salesOfferRef非空时须同时证明资源属于该销售条目；旧六参数回执不证明此关系。 */
public interface ChannelSourceValidationPort {
    Validation validate(Source source);

    /**
     * Resolve current revision only through the original owner; old providers cannot infer a new
     * head.
     */
    default Validation current(Source source) {
        return new Validation(Status.UNSUPPORTED, null, null, null, null, null);
    }

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
        BUNDLE_GROUP,
        BUNDLE_MEMBER,
        WAREHOUSE,
        INVENTORY_POLICY
    }

    enum Status {
        VALID,
        STALE,
        UNSUPPORTED,
        UNKNOWN
    }

    enum ReadMode {
        CURRENT,
        HISTORICAL
    }

    /**
     * Explicit original parents and read purpose; never discover a menu from a bare SKU or option.
     */
    record SourceContext(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String menuRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String optionGroupRef,
            @JsonDeserialize(using = CommerceWireCodecs.ReadModeDeserializer.class) ReadMode mode,
            @JsonInclude(JsonInclude.Include.NON_NULL)
                    @JsonDeserialize(using = CommerceTextDeserializer.class)
                    String variantSkuRef,
            @JsonInclude(JsonInclude.Include.NON_NULL)
                    @JsonDeserialize(using = CommerceTextDeserializer.class)
                    String bundleGroupRef) {
        public SourceContext(String menuRef, String optionGroupRef, ReadMode mode) {
            this(menuRef, optionGroupRef, mode, null, null);
        }

        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown source context field");
        }
    }

    record Source(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String entityNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String storeNo,
            @JsonDeserialize(using = CommerceWireCodecs.OwnerDeserializer.class) Owner owner,
            @JsonDeserialize(using = CommerceWireCodecs.KindDeserializer.class) Kind kind,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String sourceRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String sourceRevision,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String salesOfferRef,
            @JsonInclude(JsonInclude.Include.NON_NULL) SourceContext context) {
        public Source(
                String entityNo,
                String storeNo,
                Owner owner,
                Kind kind,
                String sourceRef,
                String sourceRevision,
                String salesOfferRef) {
            this(entityNo, storeNo, owner, kind, sourceRef, sourceRevision, salesOfferRef, null);
        }

        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown source field");
        }

        public Source(
                @JsonDeserialize(using = CommerceTextDeserializer.class) String entityNo,
                @JsonDeserialize(using = CommerceTextDeserializer.class) String storeNo,
                @JsonDeserialize(using = CommerceWireCodecs.OwnerDeserializer.class) Owner owner,
                @JsonDeserialize(using = CommerceWireCodecs.KindDeserializer.class) Kind kind,
                @JsonDeserialize(using = CommerceTextDeserializer.class) String sourceRef,
                @JsonDeserialize(using = CommerceTextDeserializer.class) String sourceRevision) {
            this(entityNo, storeNo, owner, kind, sourceRef, sourceRevision, null);
        }
    }

    record Validation(
            @JsonDeserialize(using = CommerceWireCodecs.StatusDeserializer.class) Status status,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String canonicalRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String canonicalRevision,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String contentHash,
            @JsonDeserialize(using = CommerceInstantDeserializer.class) Instant validUntil,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String ownerReceiptNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String canonicalSalesOfferRef,
            @JsonInclude(JsonInclude.Include.NON_NULL) SourceContext canonicalContext) {
        public Validation(
                Status status,
                String canonicalRef,
                String canonicalRevision,
                String contentHash,
                Instant validUntil,
                String ownerReceiptNo,
                String canonicalSalesOfferRef) {
            this(
                    status,
                    canonicalRef,
                    canonicalRevision,
                    contentHash,
                    validUntil,
                    ownerReceiptNo,
                    canonicalSalesOfferRef,
                    null);
        }

        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown source proof field");
        }

        public Validation(
                @JsonDeserialize(using = CommerceWireCodecs.StatusDeserializer.class) Status status,
                @JsonDeserialize(using = CommerceTextDeserializer.class) String canonicalRef,
                @JsonDeserialize(using = CommerceTextDeserializer.class) String canonicalRevision,
                @JsonDeserialize(using = CommerceTextDeserializer.class) String contentHash,
                @JsonDeserialize(using = CommerceInstantDeserializer.class) Instant validUntil,
                @JsonDeserialize(using = CommerceTextDeserializer.class) String ownerReceiptNo) {
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

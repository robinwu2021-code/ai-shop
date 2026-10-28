package ai.neargo.shop.spi.product;

import ai.neargo.shop.spi.media.MediaPublicSourceFacts;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Read-only original cover expectations; missing or invalid images never silently become NONE. */
public final class CatalogImageContracts {
    private CatalogImageContracts() { }

    /** Frozen consumer expectation distinguishes absent images from unresolved references. */
    public record Expectation(
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String goodsNo,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String expectedCoverKind,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String expectedCoverRef) {
        @JsonAnySetter
        public void rejectUnknown(String k, Object v) { throw unknown(); }
    }

    /** One bounded, explicitly scoped batch is resolved atomically by the original owner. */
    public record Query(
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String entityNo,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String storeNo,
            List<Expectation> expectations) {
        public Query { expectations = expectations == null ? null : List.copyOf(expectations); }
        @JsonAnySetter
        public void rejectUnknown(String k, Object v) { throw unknown(); }
    }

    /** Identity evidence deliberately carries no public URL, bytes or invented media revision. */
    public record Item(
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String goodsNo,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String coverKind,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String originalRefDigest,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String assetKey,
            @JsonDeserialize(using = CatalogRevisionDeserializer.class) Long goodsVersion,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String assetIdentityHash,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String referenceReceiptNo) {
        @JsonAnySetter
        public void rejectUnknown(String k, Object v) { throw unknown(); }
    }

    /** Short-lived original evidence must be validated again immediately before use. */
    public record Facts(
            @JsonDeserialize(using = MediaPublicSourceFacts.IntegerDeserializer.class) Integer schemaVersion,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String purpose,
            Query query, List<Item> items, Instant verifiedAt, Instant validUntil,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String receiptNo) {
        public Facts { items = items == null ? null : List.copyOf(items); }
        @JsonAnySetter
        public void rejectUnknown(String k, Object v) { throw unknown(); }
    }

    private static IllegalArgumentException unknown() { return new IllegalArgumentException("Unknown catalog image"); }
}

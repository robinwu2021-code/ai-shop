package ai.neargo.shop.spi.media;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.time.Instant;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Original media owner resolves identity only; public delivery still needs MediaPublicSourcePort. */
public interface MediaReferenceQueryPort {
    /** Older owners cannot infer ownership from an arbitrary URL. */
    default Facts resolve(Query query) {
        throw BizException.of(ErrorCode.NOT_FOUND);
    }

    /** The caller supplies the original reference, never a claimed ownership result. */
    record Query(
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String entityNo,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String storeNo,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String reference) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) { throw new IllegalArgumentException("Unknown reference"); }
    }

    /** Identity hash is deliberately not an asset content revision or a public URL. */
    record Facts(
            @JsonDeserialize(using = MediaPublicSourceFacts.IntegerDeserializer.class) Integer schemaVersion,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String purpose,
            Query query,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String assetKey,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String assetIdentityHash,
            Instant verifiedAt, Instant validUntil,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String receiptNo) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) { throw new IllegalArgumentException("Unknown reference"); }
    }
}

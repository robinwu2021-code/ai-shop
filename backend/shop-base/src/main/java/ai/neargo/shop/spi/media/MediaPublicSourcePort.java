package ai.neargo.shop.spi.media;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;
import java.util.List;

/**
 * Original public media authority is shared by catalog owners without copying uploads or private
 * signing.
 */
public interface MediaPublicSourcePort {
    default Facts capture(Request request) {
        throw BizException.of(ErrorCode.NOT_FOUND);
    }

    record Request(
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String entityNo,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String storeNo,
            @JsonDeserialize(contentUsing = MediaPublicSourceFacts.TextDeserializer.class)
                    List<String> assetKeys) {
        public Request {
            assetKeys = assetKeys == null ? null : List.copyOf(assetKeys);
        }

        @JsonAnySetter
        public void rejectUnknown(String k, Object v) {
            throw unknown();
        }
    }

    record Asset(
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String assetKey,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class)
                    String publicUrl,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class)
                    String contentType,
            @JsonDeserialize(using = CatalogRevisionDeserializer.class) Long bytes,
            @JsonDeserialize(using = MediaPublicSourceFacts.IntegerDeserializer.class)
                    Integer width,
            @JsonDeserialize(using = MediaPublicSourceFacts.IntegerDeserializer.class)
                    Integer height,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class)
                    String sourceRevision) {
        @JsonAnySetter
        public void rejectUnknown(String k, Object v) {
            throw unknown();
        }
    }

    record Facts(
            @JsonDeserialize(using = MediaPublicSourceFacts.IntegerDeserializer.class)
                    Integer schemaVersion,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String purpose,
            Request request,
            List<Asset> assets,
            Instant verifiedAt,
            Instant validUntil,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class)
                    String receiptNo) {
        public Facts {
            assets = assets == null ? null : List.copyOf(assets);
        }

        @JsonAnySetter
        public void rejectUnknown(String k, Object v) {
            throw unknown();
        }
    }

    private static IllegalArgumentException unknown() {
        return new IllegalArgumentException("Unknown public media field");
    }
}

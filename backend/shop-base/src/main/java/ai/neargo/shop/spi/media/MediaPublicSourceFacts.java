package ai.neargo.shop.spi.media;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.media.MediaPublicSourcePort.Facts;
import ai.neargo.shop.spi.media.MediaPublicSourcePort.Request;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/** Strict original media transport never coerces identifiers or dimensional metadata. */
public final class MediaPublicSourceFacts {
    private MediaPublicSourceFacts() {}

    /** Accept only bounded original keys, never caller-provided URLs or image metadata. */
    public static void validateRequest(Request request) {
        if (request == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        MediaAssetQueryPort.validateLookup(
                request.entityNo(), request.storeNo(), request.assetKeys());
    }

    /**
     * Match the original public-key layout as well as the authoritative GOODS ledger
     * classification.
     */
    public static boolean publicGoodsKey(String key) {
        if (!MediaAssetQueryPort.validKey(key)) return false;
        var segments = key.split("/");
        return segments.length <= 2 || "goods".equalsIgnoreCase(segments[2]);
    }

    /** Stable original public URLs cannot contain private signing material or path rewriting. */
    public static void validateUrl(String key, String address) {
        require(publicGoodsKey(key) && address != null && address.length() <= 2048);
        try {
            var uri = URI.create(address);
            require(
                    "https".equalsIgnoreCase(uri.getScheme())
                            && uri.getHost() != null
                            && uri.getRawUserInfo() == null
                            && uri.getRawQuery() == null
                            && uri.getRawFragment() == null
                            && uri.getRawPath() != null
                            && uri.getRawPath().endsWith("/" + key)
                            && !uri.getRawPath().contains("%")
                            && uri.normalize().equals(uri)
                            && Arrays.stream(uri.getRawPath().split("/", -1))
                                    .noneMatch(part -> ".".equals(part) || "..".equals(part))
                            && (uri.getPort() == -1
                                    || uri.getPort() > 0 && uri.getPort() <= 65535));
        } catch (RuntimeException invalid) {
            throw malformed();
        }
    }

    /**
     * An integrity digest binds the complete read result; service authentication remains separate.
     */
    public static Facts seal(Facts f) {
        return copy(f, PricingSnapshotHash.of(copy(f, null)));
    }

    private static Facts copy(Facts f, String receipt) {
        return new Facts(
                f.schemaVersion(),
                f.purpose(),
                f.request(),
                f.assets(),
                f.verifiedAt(),
                f.validUntil(),
                receipt);
    }

    /**
     * Remote readers must reject incomplete, stale, reordered or altered original media evidence.
     */
    public static void validate(Request request, Facts facts, Instant now) {
        validateRequest(request);
        require(
                facts != null
                        && Objects.equals(facts.schemaVersion(), 1)
                        && "CATALOG_PUBLIC_MEDIA".equals(facts.purpose())
                        && request.equals(facts.request())
                        && facts.assets() != null
                        && facts.assets().size() == request.assetKeys().size());
        for (int n = 0; n < facts.assets().size(); n++) {
            var a = facts.assets().get(n);
            require(
                    a != null
                            && request.assetKeys().get(n).equals(a.assetKey())
                            && a.bytes() != null
                            && a.bytes() > 0
                            && a.bytes() <= 9007199254740991L
                            && (a.width() == null || a.width() > 0)
                            && (a.height() == null || a.height() > 0)
                            && hash(a.sourceRevision())
                            && (a.contentType() == null
                                    || Set.of("image/jpeg", "image/png", "image/webp", "image/gif")
                                            .contains(a.contentType())));
            validateUrl(a.assetKey(), a.publicUrl());
        }
        require(
                now != null
                        && facts.verifiedAt() != null
                        && !facts.verifiedAt().isAfter(now)
                        && facts.validUntil() != null
                        && facts.validUntil().isAfter(now)
                        && !facts.validUntil().isAfter(facts.verifiedAt().plusSeconds(30))
                        && hash(facts.receiptNo())
                        && seal(facts).receiptNo().equals(facts.receiptNo()));
    }

    private static boolean hash(String text) {
        return text != null && text.matches("[a-f0-9]{64}");
    }

    private static void require(boolean ok) {
        if (!ok) throw malformed();
    }

    private static IllegalStateException malformed() {
        return new IllegalStateException("Invalid original public media facts");
    }

    public static final class TextDeserializer extends ValueDeserializer<String> {
        @Override
        public String deserialize(JsonParser p, DeserializationContext c) {
            if (p.currentToken() != JsonToken.VALUE_STRING)
                return c.reportInputMismatch(String.class, "String token required");
            return p.getString();
        }
    }

    public static final class IntegerDeserializer extends ValueDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser p, DeserializationContext c) {
            if (p.currentToken() != JsonToken.VALUE_NUMBER_INT)
                return c.reportInputMismatch(Integer.class, "Integer token required");
            return p.getIntValue();
        }
    }
}

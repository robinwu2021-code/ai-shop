package ai.neargo.shop.spi.media;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.media.MediaReferenceQueryPort.Facts;
import ai.neargo.shop.spi.media.MediaReferenceQueryPort.Query;
import java.time.Instant;
import java.util.Objects;

/** Integrity and scope checks are shared by local and remote original-identity consumers. */
public final class MediaReferenceFacts {
    private MediaReferenceFacts() { }

    /** Reject unbounded or coerced identity inputs before asking the original owner. */
    public static void validateQuery(Query q) {
        if (q == null || !identifier(q.entityNo()) || !identifier(q.storeNo()) || q.reference() == null
                || q.reference().isEmpty() || q.reference().length() > 2048
                || !q.reference().equals(q.reference().trim())) throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    /** Bind the complete audit result without pretending this digest authenticates its owner. */
    public static Facts seal(Facts f) { return copy(f, PricingSnapshotHash.of(copy(f, null))); }

    private static Facts copy(Facts f, String receipt) {
        return new Facts(f.schemaVersion(), f.purpose(), f.query(), f.assetKey(), f.assetIdentityHash(),
                f.verifiedAt(), f.validUntil(), receipt);
    }

    /** Recheck scope, lifetime and original GOODS identity at each consumer boundary. */
    public static void validate(Query q, Facts f, Instant now) {
        validateQuery(q);
        require(f != null && Objects.equals(f.schemaVersion(), 1) && "MEDIA_REFERENCE_IDENTITY".equals(f.purpose())
                && q.equals(f.query()) && MediaAssetQueryPort.validKey(f.assetKey()));
        var owner = new MediaAssetQueryPort.AssetFact(f.assetKey(), q.entityNo(), q.storeNo(), "GOODS", "ACTIVE");
        require(PricingSnapshotHash.of(owner).equals(f.assetIdentityHash())
                && fresh(f.verifiedAt(), f.validUntil(), now)
                && seal(f).receiptNo().equals(f.receiptNo()));
    }

    /** Shared identifier bounds prevent transport and local validation from diverging. */
    public static boolean identifier(String value) {
        return value != null && !value.isBlank() && value.length() <= 32 && value.equals(value.trim());
    }

    /** Evidence is point-in-time and never grants permanent permission to publish. */
    public static boolean fresh(Instant at, Instant until, Instant now) {
        return now != null && at != null && !at.isAfter(now) && until != null && until.isAfter(now)
                && !until.isAfter(at.plusSeconds(30));
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalStateException("Invalid original media identity facts");
    }
}

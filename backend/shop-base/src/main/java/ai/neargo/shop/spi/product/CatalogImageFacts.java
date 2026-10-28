package ai.neargo.shop.spi.product;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.media.MediaAssetQueryPort;
import ai.neargo.shop.spi.media.MediaReferenceFacts;
import ai.neargo.shop.spi.product.CatalogImageContracts.Facts;
import ai.neargo.shop.spi.product.CatalogImageContracts.Query;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;

/** Original cover integrity is checked again at the service boundary, never inferred from a URL path. */
public final class CatalogImageFacts {
    private CatalogImageFacts() { }

    /** Missing image kind must not be interpreted as an intentional empty cover. */
    public static void validateQuery(Query q) {
        if (q == null || !MediaReferenceFacts.identifier(q.entityNo()) || !MediaReferenceFacts.identifier(q.storeNo())
                || q.expectations() == null || q.expectations().isEmpty() || q.expectations().size() > 20) bad();
        var seen = new HashSet<String>();
        for (var e : q.expectations()) {
            if (e == null || !MediaReferenceFacts.identifier(e.goodsNo()) || !seen.add(e.goodsNo())) bad();
            if ("NONE".equals(e.expectedCoverKind())) {
                if (e.expectedCoverRef() != null) bad();
            } else if ("REFERENCE".equals(e.expectedCoverKind())) {
                String ref = e.expectedCoverRef();
                if (ref == null || ref.isBlank() || ref.length() > 2048 || !ref.equals(ref.trim())) bad();
            } else bad();
        }
    }

    /** Bind original results and expectations without replacing service-token authentication. */
    public static Facts seal(Facts f) { return copy(f, PricingSnapshotHash.of(copy(f, null))); }

    private static Facts copy(Facts f, String receipt) {
        return new Facts(f.schemaVersion(), f.purpose(), f.query(), f.items(), f.verifiedAt(), f.validUntil(), receipt);
    }

    /** Reject partial, stale, reordered or altered original-owner results at both deployments. */
    public static void validate(Query q, Facts f, Instant now) {
        validateQuery(q);
        require(f != null && Objects.equals(f.schemaVersion(), 1) && "CATALOG_IMAGE_IDENTITY".equals(f.purpose())
                && q.equals(f.query()) && f.items() != null && f.items().size() == q.expectations().size());
        for (int i = 0; i < f.items().size(); i++) {
            var e = q.expectations().get(i);
            var item = f.items().get(i);
            require(item != null && e.goodsNo().equals(item.goodsNo())
                    && e.expectedCoverKind().equals(item.coverKind()) && hash(item.originalRefDigest())
                    && item.goodsVersion() != null && item.goodsVersion() >= 0);
            if ("NONE".equals(item.coverKind())) {
                require(item.assetKey() == null && item.assetIdentityHash() == null && item.referenceReceiptNo() == null
                        && PricingSnapshotHash.of("").equals(item.originalRefDigest()));
            } else {
                require(MediaAssetQueryPort.validKey(item.assetKey()) && hash(item.referenceReceiptNo())
                        && PricingSnapshotHash.of(new MediaAssetQueryPort.AssetFact(item.assetKey(), q.entityNo(),
                                q.storeNo(), "GOODS", "ACTIVE")).equals(item.assetIdentityHash()));
            }
        }
        require(MediaReferenceFacts.fresh(f.verifiedAt(), f.validUntil(), now)
                && seal(f).receiptNo().equals(f.receiptNo()));
    }

    private static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static void bad() { throw BizException.of(ErrorCode.BAD_REQUEST); }
    private static void require(boolean valid) {
        if (!valid) throw new IllegalStateException("Invalid original catalog image facts");
    }
}

package ai.neargo.shop.commerce.policy;

import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Facts;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Query;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelConnectionIdentityPort.Identity;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Short-lived original authority evidence is checked on both sides of a trusted transport. */
public final class ChannelCatalogAuthorityPolicy {
    private ChannelCatalogAuthorityPolicy() {}

    public static void validateQuery(Query query) {
        if (query == null
                || !text(query.entityNo(), 32)
                || !text(query.storeNo(), 32)
                || !text(query.connectionNo(), 32)
                || !text(query.revisionNo(), 32)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    public static Identity identity(Facts facts) {
        return new Identity(
                facts.query().entityNo(),
                facts.query().storeNo(),
                facts.account().accountNo(),
                facts.profile().profileNo(),
                facts.account().externalAccountRef(),
                facts.connection().externalStoreRef(),
                facts.profile().identityNamespace(),
                facts.profile().accountScoped(),
                facts.account().version(),
                facts.profile().version());
    }

    public static Facts seal(Facts facts) {
        return copy(facts, PricingSnapshotHash.of(copy(facts, null)));
    }

    private static Facts copy(Facts f, String receipt) {
        return new Facts(
                f.schemaVersion(),
                f.purpose(),
                f.query(),
                f.provider(),
                f.profile(),
                f.account(),
                f.connection(),
                f.grant(),
                f.identity(),
                f.verifiedAt(),
                f.validUntil(),
                receipt);
    }

    public static void validate(Query query, Facts f, Instant now) {
        validateQuery(query);
        require(
                f != null
                        && Objects.equals(f.schemaVersion(), 1)
                        && "CATALOG_AUTHORITY".equals(f.purpose())
                        && query.equals(f.query())
                        && f.provider() != null
                        && f.profile() != null
                        && f.account() != null
                        && f.connection() != null
                        && f.grant() != null
                        && f.identity() != null
                        && now != null);
        validateBase(f);
        var g = f.grant();
        var i = f.identity();
        require(
                "VERIFIED".equals(i.status())
                        && text(i.receiptRef(), 128)
                        && PricingSnapshotHash.of(identity(f)).equals(i.identityHash())
                        && f.verifiedAt() != null
                        && !f.verifiedAt().isAfter(now)
                        && f.validUntil() != null
                        && f.validUntil().isAfter(now)
                        && !f.validUntil().isAfter(f.verifiedAt().plusSeconds(30))
                        && !f.validUntil().isAfter(g.expiresAt())
                        && i.verifiedAt() != null
                        && i.validUntil() != null
                        && !i.verifiedAt().isAfter(f.verifiedAt())
                        && i.validUntil().isAfter(i.verifiedAt())
                        && !f.validUntil().isAfter(i.validUntil())
                        && hash(f.receiptNo())
                        && seal(f).receiptNo().equals(f.receiptNo()));
    }

    /** Validate database projections before any remote verifier receives the identity. */
    public static void validateBase(Facts f) {
        require(
                f != null
                        && f.query() != null
                        && f.provider() != null
                        && f.profile() != null
                        && f.account() != null
                        && f.connection() != null
                        && f.grant() != null);
        var query = f.query();
        var p = f.provider();
        var profile = f.profile();
        var a = f.account();
        var c = f.connection();
        var g = f.grant();
        require(
                text(p.providerNo(), 32)
                        && text(p.providerCode(), 32)
                        && version(p.version())
                        && text(profile.profileNo(), 32)
                        && text(profile.productCode(), 64)
                        && text(profile.protocolCode(), 64)
                        && profile.market() != null
                        && profile.market().matches("[A-Z]{2}")
                        && text(profile.identityNamespace(), 128)
                        && profile.accountScoped() != null
                        && version(profile.version())
                        && text(a.accountNo(), 32)
                        && text(a.externalAccountRef(), 128)
                        && version(a.version())
                        && query.connectionNo().equals(c.connectionNo())
                        && query.revisionNo().equals(c.revisionNo())
                        && text(c.externalStoreRef(), 128)
                        && hash(c.channelStoreKey())
                        && hash(c.configHash())
                        && Set.of("FOOD", "RETAIL")
                                .contains(c.sourceOwner() == null ? "" : c.sourceOwner())
                        && "LOCAL".equals(c.catalogAuthority())
                        && Set.of("INSTANT", "PARCEL", "REDEMPTION")
                                .contains(
                                        c.fulfillmentProfile() == null
                                                ? ""
                                                : c.fulfillmentProfile())
                        && version(c.version())
                        && version(c.revisionVersion())
                        && positive(c.revision())
                        && text(g.authorizationNo(), 32)
                        && version(g.version())
                        && positive(g.revision())
                        && "CATALOG".equals(g.capability())
                        && hash(g.capabilityHash())
                        && g.expiresAt() != null);
        require(
                ChannelIdentity.storeKey(
                                profile.identityNamespace(),
                                profile.accountScoped(),
                                a.externalAccountRef(),
                                c.externalStoreRef())
                        .equals(c.channelStoreKey()));
    }

    private static boolean version(Long v) {
        return v != null && v >= 0 && v <= 9007199254740991L;
    }

    private static boolean positive(Long v) {
        return version(v) && v > 0;
    }

    private static boolean hash(String v) {
        return v != null && v.matches("[a-f0-9]{64}");
    }

    private static boolean text(String v, int max) {
        return v != null && !v.isBlank() && v.length() <= max && v.equals(v.strip());
    }

    private static void require(boolean ok) {
        if (!ok) throw new IllegalStateException("Invalid original catalog authority facts");
    }
}

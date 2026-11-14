package ai.neargo.shop.trade.policy;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import ai.neargo.auth.store.SubjectKind;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingCapabilities;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.common.pricing.PricingRuleCatalog;
import ai.neargo.shop.common.pricing.SystemConstraints;
import ai.neargo.shop.spi.trade.PricingConfigEvidencePort;
import ai.neargo.shop.spi.trade.PricingConfigEvidencePort.Scope;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.trade.dto.PricingConfigCommands;
import ai.neargo.shop.trade.dto.PricingConfigCommands.Context;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Trusted identity is resolved once, before services can bypass data scope for their explicit predicates. */
@Component
public class PricingAccessPolicy {
    private final StoreDirectoryPort stores;
    private final ObjectProvider<PricingConfigEvidencePort> providers;

    public PricingAccessPolicy(StoreDirectoryPort stores, ObjectProvider<PricingConfigEvidencePort> providers) {
        this.stores = stores;
        this.providers = providers;
    }

    /** An expired, zero-capability context permits only draft/read operations; never publication. */
    public Context draft(boolean template) {
        var user = SecurityUtils.requireUser();
        if (!"MAIN".equals(user.tenantNo())) throw BizException.of(ErrorCode.FORBIDDEN);
        String entity = null;
        String store = null;
        if (template) {
            if (user.realm() != Realm.OPERATOR || user.subjectKind() != SubjectKind.OPS) {
                throw BizException.of(ErrorCode.FORBIDDEN);
            }
        } else {
            if (user.realm() != Realm.MERCHANT) throw BizException.of(ErrorCode.FORBIDDEN);
            entity = BizContext.requireMerchantNo();
            store = BizContext.requireStoreNo();
            if (!BizContext.current().storeNos().contains(store)) throw BizException.of(ErrorCode.FORBIDDEN);
            var card = stores.cards(List.of(store)).get(store);
            if (card == null || !store.equals(card.storeNo()) || !entity.equals(card.entityNo())) {
                throw BizException.of(ErrorCode.FORBIDDEN);
            }
        }
        return new Context(user.tenantNo(), user.userNo(), template, entity, store,
                new PricingCapabilities(0, 0, false, false, false), "DRAFT_ONLY", Instant.EPOCH, Set.of());
    }

    /** Missing or ambiguous providers cannot silently turn examples into live capabilities or trusted tax sources. */
    public Context publication(boolean template, String resourceNo) {
        Context identity = draft(template);
        try {
            PricingConfigCommands.identity(resourceNo);
        } catch (IllegalArgumentException invalid) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (template) {
            // Global defaults are definitions, not an execution grant for any merchant or financial owner.
            var catalog = PricingSnapshotHash.of(List.of("PRICING_TEMPLATE_DEFINITION_V1",
                    PricingRuleCatalog.registryVersion(2), SystemConstraints.standard().rules(2),
                    PricingRuleCatalog.registryVersion(3), SystemConstraints.standard().rules(3)));
            long bucket = Math.floorDiv(Instant.now().getEpochSecond(), 300);
            return new Context(identity.tenantNo(), identity.actorNo(), true, null, null,
                    new PricingCapabilities(0, 0, false, false, false), "DEF" + catalog.substring(0, 29),
                    Instant.ofEpochSecond((bucket + 1) * 300), Set.of());
        }
        var provider = providers.getIfUnique();
        if (provider == null) throw blocked();
        var scope = new Scope(identity.tenantNo(), identity.entityNo(), identity.storeNo(), resourceNo, template);
        var evidence = provider.load(scope);
        Instant now = Instant.now();
        if (evidence == null || !Objects.equals(scope, evidence.scope()) || evidence.capabilities() == null
                || evidence.expiresAt() == null || !now.isBefore(evidence.expiresAt())
                || evidence.expiresAt().isAfter(now.plusSeconds(300)) || evidence.sources().isEmpty()) {
            throw blocked();
        }
        try {
            return new Context(identity.tenantNo(), identity.actorNo(), template, identity.entityNo(),
                    identity.storeNo(), evidence.capabilities(), evidence.reference(), evidence.expiresAt(),
                    evidence.verifiedTaxProfiles());
        } catch (IllegalArgumentException invalid) {
            throw blocked();
        }
    }

    private static BizException blocked() { return BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED); }
}

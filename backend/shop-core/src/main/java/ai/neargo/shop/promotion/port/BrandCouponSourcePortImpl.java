package ai.neargo.shop.promotion.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.member.service.BrandAudienceSnapshotService;
import ai.neargo.shop.promotion.dto.BrandCouponModels.Source;
import ai.neargo.shop.promotion.service.ActivityCommandDeduplicator;
import ai.neargo.shop.promotion.service.BrandCouponService;
import ai.neargo.shop.spi.entitlement.BrandCouponSourcePort;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementIssueAudiencePort.AudienceSnapshot;
import ai.neargo.shop.spi.marketing.BrandPromotionScopePort;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Reverse facts prove the exact Core-owned definition, frozen participating stores and original issuing command. */
@Component
public class BrandCouponSourcePortImpl implements BrandCouponSourcePort {
    private final BrandCouponService sources;
    private final BrandAudienceSnapshotService audiences;
    private final BrandPromotionScopePort brands;
    private final ObjectMapper json;

    public BrandCouponSourcePortImpl(BrandCouponService sources, BrandAudienceSnapshotService audiences,
                                     BrandPromotionScopePort brands, ObjectMapper json) {
        this.sources = sources;
        this.audiences = audiences;
        this.brands = brands;
        this.json = json;
    }

    @Override
    public void validateTemplate(String tenantNo, String issuerEntityNo, EntitlementDefinition definition) {
        Source source = exact(tenantNo, issuerEntityNo, definition);
        var brand = brands.find(tenantNo, source.managementNo(), source.brandNo(), issuerEntityNo);
        if (brand == null) throw BizException.of(ErrorCode.NOT_FOUND);
        if (!source.snapshot().stores().stream().allMatch(frozen -> brand.stores().stream().anyMatch(current ->
                frozen.storeNo().equals(current.storeNo()) && frozen.entityNo().equals(current.entityNo())))) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    @Override
    public boolean permitsUse(String tenantNo, String issuerEntityNo, EntitlementDefinition definition,
                              String templateVersionNo, String consumerEntityNo, String storeNo) {
        Source source = exact(tenantNo, issuerEntityNo, definition);
        return List.of("ACTIVE", "STOPPED").contains(source.status())
                && templateVersionNo.equals(source.nativeVersionNo())
                && source.snapshot().stores().stream().anyMatch(store -> storeNo.equals(store.storeNo())
                && consumerEntityNo.equals(store.entityNo()))
                && brands.storeBelongsTo(tenantNo, storeNo, consumerEntityNo);
    }

    @Override
    public AudienceSnapshot audience(String tenantNo, String issuerEntityNo, EntitlementDefinition definition,
                                      String templateVersionNo, String audienceFactRef, String actorNo,
                                      String commandNo) {
        Source source = exact(tenantNo, issuerEntityNo, definition);
        if (!List.of("ACTIVE", "STOPPED").contains(source.status())
                || !templateVersionNo.equals(source.nativeVersionNo())) throw BizException.of(ErrorCode.CONFLICT);
        var context = new BrandAudienceSnapshotService.Context(tenantNo, source.managementNo(), source.brandNo(),
                issuerEntityNo, actorNo);
        var snapshot = audiences.get(context, audienceFactRef, source.sourceNo(), templateVersionNo);
        var original = sources.issuedCommand(tenantNo, issuerEntityNo, actorNo, commandNo);
        if (!commandNo.equals(snapshot.claimedCommandNo()) || !source.sourceNo().equals(original.sourceNo())
                || !audienceFactRef.equals(original.request().get("audienceSnapshotNo"))) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return new AudienceSnapshot(tenantNo, issuerEntityNo, audienceFactRef, snapshot.recipients());
    }

    private Source exact(String tenantNo, String issuerEntityNo, EntitlementDefinition definition) {
        var scope = definition.redemptionScope();
        if (!"BRAND_STORES".equals(scope.mode()) || scope.refs().size() != 1) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        Source source = sources.sourceFact(tenantNo, issuerEntityNo, scope.refs().getFirst());
        if (!source.definitionHash().equals(ActivityCommandDeduplicator.hash(json.writeValueAsString(definition)))) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return source;
    }
}

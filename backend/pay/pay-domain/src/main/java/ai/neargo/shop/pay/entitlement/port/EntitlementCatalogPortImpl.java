package ai.neargo.shop.pay.entitlement.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.CatalogViews.PublishedVersionView;
import ai.neargo.shop.pay.entitlement.service.EntitlementCatalogService;
import ai.neargo.shop.pay.entitlement.policy.EntitlementRuleValidator;
import java.time.Instant;
import ai.neargo.shop.spi.entitlement.EntitlementCatalogPort;
import org.springframework.stereotype.Component;

/** Local catalog adapter; independent deployment must use the authenticated internal API adapter before rollout. */
@Component
public class EntitlementCatalogPortImpl implements EntitlementCatalogPort {
    private final EntitlementCatalogService catalog;
    private final EntitlementRuleValidator rules = new EntitlementRuleValidator();

    public EntitlementCatalogPortImpl(EntitlementCatalogService catalog) {
        this.catalog = catalog;
    }

    @Override
    public PublishedDefinition getPublishedVersion(String tenantNo, String entityNo, String versionNo) {
        var version = catalog.getVersion(new CatalogScope(tenantNo, entityNo, "SYSTEM", false), versionNo);
        return view(version);
    }

    @Override
    public PublishedDefinition validateReference(ReferenceQuery query) {
        if (query == null || query.currency() == null || query.purpose() == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var scope = new CatalogScope(query.tenantNo(), query.entityNo(), "SYSTEM", false);
        var version = catalog.getVersion(scope, query.versionNo());
        var head = catalog.get(scope, version.templateNo());
        var definition = version.definition();
        if (!"ACTIVE".equals(head.status()) || !query.currency().equals(definition.currency())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        rules.validatePublicationTime(definition, Instant.now());
        switch (query.purpose()) {
            case "ACTIVITY_GRANT" -> {
                if ("PAID_CARD".equals(definition.kind()) || !definition.allowedIssueModes().contains("ACTIVITY")) {
                    throw BizException.of(ErrorCode.CONFLICT);
                }
            }
            case "PAID_PRODUCT" -> {
                if (!"PAID_CARD".equals(definition.kind()) || !definition.allowedIssueModes().contains("PAID_ORDER")) {
                    throw BizException.of(ErrorCode.CONFLICT);
                }
            }
            default -> throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return view(version);
    }

    private PublishedDefinition view(PublishedVersionView version) {
        return new PublishedDefinition(version.templateNo(), version.versionNo(), version.definition(),
                version.snapshotHash());
    }
}

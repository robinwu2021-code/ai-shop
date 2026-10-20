package ai.neargo.shop.pay.entitlement.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.CatalogViews.PublishedVersionView;
import ai.neargo.shop.pay.entitlement.dto.CatalogViews.TemplateView;
import ai.neargo.shop.pay.entitlement.dto.IssuePolicyViews.PolicyView;
import ai.neargo.shop.pay.entitlement.dto.IssuePolicyViews.SavePolicy;
import ai.neargo.shop.pay.entitlement.dto.ManagedTemplateView;
import ai.neargo.shop.pay.entitlement.service.EntitlementCatalogService;
import ai.neargo.shop.pay.entitlement.service.EntitlementIssuePolicyService;
import ai.neargo.shop.pay.entitlement.service.EntitlementManagementService;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import org.springframework.stereotype.Service;

/** Aggregates only pay-owned facts; transport must not reach into core database tables. */
@Service
public class EntitlementManagementServiceImpl implements EntitlementManagementService {
    private final EntitlementCatalogService catalog;
    private final EntitlementIssuePolicyService policies;
    public EntitlementManagementServiceImpl(EntitlementCatalogService catalog, EntitlementIssuePolicyService policies) {
        this.catalog = catalog;
        this.policies = policies;
    }
    @Override
    public PageData<ManagedTemplateView> list(CatalogScope scope, String kind, String status, long page, long size) {
        var p = catalog.list(scope, kind, status, page, size);
        return PageData.of(p.records().stream().map(v -> managed(scope, v)).toList(), p.total(), p.page(), p.size());
    }
    @Override
    public ManagedTemplateView get(CatalogScope scope, String no) {
        return managed(scope, catalog.get(scope, no));
    }
    @Override
    public ManagedTemplateView create(CatalogScope scope, EntitlementDefinition d, String key) {
        return written(catalog.create(scope, d, key));
    }
    @Override
    public ManagedTemplateView draft(CatalogScope scope, String no, long revision, EntitlementDefinition d,
            String key) {
        return written(catalog.saveDraft(scope, no, revision, d, key));
    }
    @Override
    public PublishedVersionView publish(CatalogScope scope, String no, long revision, String key) {
        return catalog.publish(scope, no, revision, key);
    }
    @Override
    public ManagedTemplateView status(CatalogScope scope, String no, long revision, String status, String key) {
        return written(catalog.changeStatus(scope, no, revision, status, key));
    }
    @Override
    public PolicyView policy(CatalogScope scope, String no, SavePolicy command, String key) {
        return policies.save(scope, no, command, key);
    }
    private ManagedTemplateView written(TemplateView v) {
        return new ManagedTemplateView(v.templateNo(), v.status(), v.revision(), v.currentVersionNo(), v.draft(), null);
    }
    private ManagedTemplateView managed(CatalogScope scope, TemplateView v) {
        PolicyView policy;
        try { policy = policies.get(scope, v.templateNo()); }
        catch (BizException absent) {
            if (absent.errorCode() != ErrorCode.NOT_FOUND) throw absent;
            policy = null;
        }
        return new ManagedTemplateView(v.templateNo(), v.status(), v.revision(), v.currentVersionNo(), v.draft(),
                policy);
    }
}

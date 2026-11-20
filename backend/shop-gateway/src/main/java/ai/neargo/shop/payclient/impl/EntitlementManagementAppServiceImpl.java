package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.CatalogViews.PublishedVersionView;
import ai.neargo.shop.pay.entitlement.dto.IssuePolicyViews.PolicyView;
import ai.neargo.shop.pay.entitlement.dto.IssuePolicyViews.SavePolicy;
import ai.neargo.shop.pay.entitlement.dto.ManagedTemplateView;
import ai.neargo.shop.payclient.EntitlementManagementAppService;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ai.neargo.shop.pay.entitlement.service.EntitlementManagementService;

@Service
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "embedded", matchIfMissing = true)
public class EntitlementManagementAppServiceImpl implements EntitlementManagementAppService {
    private final EntitlementManagementService pay;
    public EntitlementManagementAppServiceImpl(EntitlementManagementService pay) { this.pay = pay; }
    @Override
    public PageData<ManagedTemplateView> list(CatalogScope s, String kind, String status, long page, long size) {
        return pay.list(s, kind, status, page, size);
    }
    @Override
    public ManagedTemplateView get(CatalogScope s, String no) {
        return pay.get(s, no);
    }
    @Override
    public ManagedTemplateView create(CatalogScope s, EntitlementDefinition d, String key) {
        return pay.create(s, d, key);
    }
    @Override
    public ManagedTemplateView draft(CatalogScope s, String no, long revision, EntitlementDefinition d, String key) {
        return pay.draft(s, no, revision, d, key);
    }
    @Override
    public PublishedVersionView publish(CatalogScope s, String no, long revision, String key) {
        return pay.publish(s, no, revision, key);
    }
    @Override
    public ManagedTemplateView status(CatalogScope s, String no, long revision, String status, String key) {
        return pay.status(s, no, revision, status, key);
    }
    @Override
    public PolicyView policy(CatalogScope s, String no, SavePolicy command, String key) {
        return pay.policy(s, no, command, key);
    }
}

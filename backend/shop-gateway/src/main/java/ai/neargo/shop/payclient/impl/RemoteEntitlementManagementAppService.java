package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.CatalogViews.PublishedVersionView;
import ai.neargo.shop.pay.entitlement.dto.IssuePolicyViews.PolicyView;
import ai.neargo.shop.pay.entitlement.dto.IssuePolicyViews.SavePolicy;
import ai.neargo.shop.pay.entitlement.dto.ManagedTemplateView;
import ai.neargo.shop.payclient.EntitlementManagementAppService;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PayEntitlementRequests;

@Service
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "standalone")
public class RemoteEntitlementManagementAppService implements EntitlementManagementAppService {
    private final PayInternalApi pay;
    public RemoteEntitlementManagementAppService(PayInternalApi pay) { this.pay = pay; }
    @Override
    public PageData<ManagedTemplateView> list(CatalogScope s, String kind, String status, long page, long size) {
        return RemoteEntitlementCalls.call(() -> pay.entTemplates(new PayEntitlementRequests.ListTemplates(s,
                kind, status, page, size)));
    }
    @Override
    public ManagedTemplateView get(CatalogScope s, String no) {
        return RemoteEntitlementCalls.call(() -> pay.entTemplate(new PayEntitlementRequests.GetTemplate(s, no)));
    }
    @Override
    public ManagedTemplateView create(CatalogScope s, EntitlementDefinition d, String key) {
        return RemoteEntitlementCalls.call(() -> pay.entCreate(new PayEntitlementRequests.CreateTemplate(s, d, key)));
    }
    @Override
    public ManagedTemplateView draft(CatalogScope s, String no, long revision, EntitlementDefinition d, String key) {
        return RemoteEntitlementCalls.call(() -> pay.entDraft(new PayEntitlementRequests.DraftTemplate(s, no,
                revision, d, key)));
    }
    @Override
    public PublishedVersionView publish(CatalogScope s, String no, long revision, String key) {
        return RemoteEntitlementCalls.call(() -> pay.entPublish(new PayEntitlementRequests.PublishTemplate(s, no,
                revision, key)));
    }
    @Override
    public ManagedTemplateView status(CatalogScope s, String no, long revision, String status, String key) {
        return RemoteEntitlementCalls.call(() -> pay.entStatus(new PayEntitlementRequests.StatusTemplate(s, no,
                revision, status, key)));
    }
    @Override
    public PolicyView policy(CatalogScope s, String no, SavePolicy command, String key) {
        return RemoteEntitlementCalls.call(() -> pay.entIssuePolicy(new PayEntitlementRequests.ConfigureIssue(s,
                no, command, key)));
    }
}

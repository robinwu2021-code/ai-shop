package ai.neargo.shop.payclient.impl;
import ai.neargo.shop.pay.client.*;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.migration.dto.LegacyCouponTemplateViews.*;
import ai.neargo.shop.payclient.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
@Service
@ConditionalOnProperty(name="shop.pay.deployment",havingValue="standalone")
public class RemoteLegacyCouponTemplateAppService implements LegacyCouponTemplateAppService {
    private final PayInternalApi pay;
    public RemoteLegacyCouponTemplateAppService(PayInternalApi pay) {this.pay=pay;}
    public Snapshot archive(CatalogScope s,Reference r,boolean archived,String key) {return RemoteEntitlementCalls.call(()->pay.legacyTemplateArchive(new PayLegacyTemplateRequests.Archive(s,r,archived,key)));}
    public Snapshot get(CatalogScope s,Reference r) {return RemoteEntitlementCalls.call(()->pay.legacyTemplateGet(new PayLegacyTemplateRequests.Get(s,r)));}
    public Snapshot status(CatalogScope s,Reference r,String status,String key) {return RemoteEntitlementCalls.call(()->pay.legacyTemplateStatus(new PayLegacyTemplateRequests.Status(s,r,status,key)));}
    public Snapshot save(CatalogScope s,Reference r,DefinitionChange c,String key) {return RemoteEntitlementCalls.call(()->pay.legacyTemplateSave(new PayLegacyTemplateRequests.Save(s,r,c,key)));}
}

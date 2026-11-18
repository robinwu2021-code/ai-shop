package ai.neargo.shop.payclient.impl;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.migration.dto.LegacyCouponTemplateViews.*;
import ai.neargo.shop.pay.migration.service.LegacyCouponTemplateManagementService;
import ai.neargo.shop.payclient.LegacyCouponTemplateAppService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
@Service
@ConditionalOnProperty(name="shop.pay.deployment",havingValue="embedded",matchIfMissing=true)
public class LegacyCouponTemplateAppServiceImpl implements LegacyCouponTemplateAppService {
    private final LegacyCouponTemplateManagementService pay;
    public LegacyCouponTemplateAppServiceImpl(LegacyCouponTemplateManagementService pay) {this.pay=pay;}
    public Snapshot save(CatalogScope s,Reference r,DefinitionChange c,String key) {return pay.save(s,r,c,key);}
    public Snapshot archive(CatalogScope s,Reference r,boolean archived,String key) {return pay.archive(s,r,archived,key);}
    public Snapshot get(CatalogScope s,Reference r) {return pay.get(s,r);}
    public Snapshot status(CatalogScope s,Reference r,String status,String key) {return pay.status(s,r,status,key);}
}

package ai.neargo.shop.paybridge;
import ai.neargo.shop.common.*;
import ai.neargo.shop.merchant.brand.*;
import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.payclient.StoredValueIssuerAppService;
import ai.neargo.shop.promotion.service.StoredValueActivityModels.Scope;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.View;
import org.springframework.stereotype.Service;
@Service
public class BrandStoredValueActivityContextService {
    public record Context(Scope scope,View issuer) { }
    private final BrandStoredValueActivityAuthority authority;private final StoredValueIssuerAppService issuers;
    public BrandStoredValueActivityContextService(BrandStoredValueActivityAuthority authority,StoredValueIssuerAppService issuers){this.authority=authority;this.issuers=issuers;}
    public Context resolve(Actor actor,String management,String brand,String currency,String permission){
        var scope=authority.lookup(actor,management,brand,permission);var lookup=issuers.find(scope);var issuer=lookup==null?null:lookup.issuer();
        if(issuer==null)throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);if(currency!=null&&!currency.equals(issuer.currency()))throw BizException.of(ErrorCode.BAD_REQUEST);
        var a=authority.activity(actor,management,brand,issuer,permission);return new Context(new Scope(a.policyScope(),a.entityNo(),a.actorNo(),a.principalNo(),a.authorityRevision()),issuer);
    }
}

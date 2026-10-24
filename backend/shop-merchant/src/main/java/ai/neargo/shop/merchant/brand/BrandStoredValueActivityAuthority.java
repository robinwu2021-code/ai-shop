package ai.neargo.shop.merchant.brand;

import static ai.neargo.shop.merchant.brand.ManagementRepository.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.*;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Brand operational permission is independent of settlement configuration and store activity permission. */
@Service
public class BrandStoredValueActivityAuthority {
    private final ManagementRepository db;private final ManagementScopeService scopes;
    private static final Set<String> PERMISSIONS=Set.of("sv.brand.activity.read","sv.brand.activity.manage","sv.brand.activity.publish","sv.brand.activity.approve");
    public BrandStoredValueActivityAuthority(ManagementRepository db,ManagementScopeService scopes){this.db=db;this.scopes=scopes;}
    private Map<String,Object> require(Actor actor,String management,String brand,String permission){
        ContractChecks.require(PERMISSIONS.contains(permission));scopes.resolve(actor,management);scopes.require(actor,management,"BRAND",brand,permission);
        var row=db.one("SELECT * FROM mch_brand WHERE tenant_no=? AND management_no=? AND brand_no=? AND status='ACTIVE' AND deleted=0",actor.tenantNo(),management,brand);if(row==null)throw BizException.of(ErrorCode.NOT_FOUND);return row;
    }
    public AuthorizedScope lookup(Actor actor,String management,String brand,String permission){require(actor,management,brand,permission);return new AuthorizedScope(actor.tenantNo(),actor.actorKey(),management,"BRAND",brand,num(scopes.space(actor,management),"auth_revision"),null,Set.of(),List.of());}
    public ActivityAuthority activity(Actor actor,String management,String brand,View issuer,String permission){
        var source=require(actor,management,brand,permission);
        if(issuer==null||!"BRAND".equals(issuer.scopeType())||!brand.equals(issuer.scopeNo())||db.one("SELECT entity_no FROM mch_entity WHERE tenant_no=? AND entity_no=? AND status='ACTIVE' AND deleted=0",actor.tenantNo(),issuer.settlementEntityNo())==null)throw BizException.of(ErrorCode.NOT_FOUND);
        String principal=actor.actorKey();if(principal.startsWith("MCH:")){var account=db.one("SELECT user_no FROM mch_account WHERE tenant_no=? AND mch_account_no=? AND status='ACTIVE' AND deleted=0",actor.tenantNo(),principal.substring(4));if(account==null)throw BizException.of(ErrorCode.FORBIDDEN);if(str(account,"user_no")!=null)principal="USER:"+str(account,"user_no");}
        return new ActivityAuthority(new BrandScope(actor.tenantNo(),management,brand,issuer.issuerNo(),issuer.currency(),num(source,"version")),issuer.settlementEntityNo(),actor.actorKey(),principal,num(scopes.space(actor,management),"auth_revision"));
    }
    public boolean can(Actor actor,String management,String brand,String permission){ContractChecks.require(PERMISSIONS.contains(permission));return scopes.can(actor,management,"BRAND",brand,permission);}
    public List<String> approvalBrands(Actor actor,String management){scopes.resolve(actor,management);var rows=db.rows("SELECT brand_no FROM mch_brand WHERE tenant_no=? AND management_no=? AND status='ACTIVE' AND deleted=0 LIMIT 1001",actor.tenantNo(),management);if(rows.size()>1000)throw BizException.of(ErrorCode.CONFLICT);return rows.stream().map(r->str(r,"brand_no")).filter(b->can(actor,management,b,"sv.brand.activity.approve")).toList();}
}

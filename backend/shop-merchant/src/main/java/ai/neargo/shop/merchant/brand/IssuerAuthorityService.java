package ai.neargo.shop.merchant.brand;

import static ai.neargo.shop.merchant.brand.ManagementRepository.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Resolves live organization and resource authorization before any Pay-local transaction. */
@Service
public class IssuerAuthorityService {
    private final ManagementRepository db;
    private final ManagementScopeService scopes;
    private final PaymentMerchantScopeService merchants;
    public IssuerAuthorityService(ManagementRepository db, ManagementScopeService scopes, PaymentMerchantScopeService merchants) {
        this.db=db; this.scopes=scopes; this.merchants=merchants;
    }
    public AuthorizedScope readScope(Actor actor, String management, String type, String no) {
        return scope(actor,management,type,no,"sv.issuer.read",Set.of(),List.of());
    }
    public AuthorizedScope configureScope(Actor actor, String management, String type, String no, Config config) {
        var base=scope(actor,management,type,no,"sv.issuer.configure",Set.of(),List.of());
        String entity=config.settlementEntityNo();
        scopes.require(actor,management,"ENTITY",entity,"sv.issuer.settlement.authorize");
        if(db.one("SELECT entity_no FROM mch_entity WHERE tenant_no=? AND entity_no=? AND status='ACTIVE' AND deleted=0",actor.tenantNo(),entity)==null)
            throw BizException.of(ErrorCode.FORBIDDEN);
        if("STORE".equals(type)&&!entity.equals(base.operatingEntityNo())) throw BizException.of(ErrorCode.FORBIDDEN);
        for(var route:config.routes()) {
            // P3 permits only the explicitly accepted settlement entity; multi-entity collection opens with clearing later.
            if(!entity.equals(route.entityNo())) throw BizException.of(ErrorCode.FORBIDDEN);
            if("MERCHANT_OFFLINE".equals(route.channel())) {
                if(route.paymentMerchantNo()!=null||!"OFFLINE".equals(route.scene())) throw BizException.of(ErrorCode.BAD_REQUEST);
                if("STORE".equals(type)&&!no.equals(route.storeNo())) throw BizException.of(ErrorCode.FORBIDDEN);
                if("BRAND".equals(type)&&route.storeNo()!=null) throw BizException.of(ErrorCode.BAD_REQUEST);
            } else {
                String store="STORE".equals(type)?no:route.storeNo();
                var candidates=merchants.candidates(actor,management,entity,route.channel(),route.scene(),store);
                boolean matched=candidates.stream().anyMatch(row->Objects.equals(route.paymentMerchantNo(),row.get("payMerchantNo"))
                        &&Objects.equals(Objects.toString(route.storeNo(),""),Objects.toString(row.get("storeNo"),"")));
                if(!matched) throw BizException.of(ErrorCode.FORBIDDEN);
            }
        }
        return new AuthorizedScope(base.tenantNo(),base.actorNo(),base.managementNo(),base.scopeType(),base.scopeNo(),
                base.authorityRevision(),base.operatingEntityNo(),Set.of(entity),config.routes());
    }
    private AuthorizedScope scope(Actor actor,String management,String type,String no,String permission,Set<String> entities,List<Route> routes) {
        if(!Set.of("STORE","BRAND").contains(type)) throw BizException.of(ErrorCode.BAD_REQUEST);
        scopes.resolve(actor,management); scopes.require(actor,management,type,no,permission);
        String entity=null;
        if("STORE".equals(type)) {
            var store=db.one("SELECT s.entity_no FROM mch_store s JOIN mch_entity e ON e.tenant_no=s.tenant_no AND e.entity_no=s.entity_no WHERE s.tenant_no=? AND s.store_no=? AND s.status='ACTIVE' AND s.deleted=0 AND e.status='ACTIVE' AND e.deleted=0",actor.tenantNo(),no);
            if(store==null) throw BizException.of(ErrorCode.NOT_FOUND);
            entity=str(store,"entity_no");
        } else if(db.one("SELECT brand_no FROM mch_brand WHERE tenant_no=? AND management_no=? AND brand_no=? AND status IN ('DRAFT','ACTIVE') AND deleted=0",actor.tenantNo(),management,no)==null)
            throw BizException.of(ErrorCode.NOT_FOUND);
        return new AuthorizedScope(actor.tenantNo(),actor.actorKey(),management,type,no,
                num(scopes.space(actor,management),"auth_revision"),entity,entities,routes);
    }
}

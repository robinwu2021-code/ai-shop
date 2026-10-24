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
    /** Existing store owners may prepare original liability metadata, never activate brand finance. */
    public StoreAuthority storePreparationScope(Actor actor,String storeNo) {
        ai.neargo.shop.spi.entitlement.ContractChecks.id(storeNo);
        var row=db.one("SELECT s.store_no,s.entity_no,s.name,s.version FROM mch_store s JOIN mch_entity e ON e.tenant_no=s.tenant_no AND e.entity_no=s.entity_no WHERE s.tenant_no=? AND s.store_no=? AND s.status='ACTIVE' AND s.deleted=0 AND e.status='ACTIVE' AND e.deleted=0",actor.tenantNo(),storeNo);
        if(row==null)throw BizException.of(ErrorCode.NOT_FOUND);
        String entity=str(row,"entity_no");
        if(scopes.ownerAccounts(actor).stream().noneMatch(account->entity.equals(str(account,"entity_no"))))throw BizException.of(ErrorCode.FORBIDDEN);
        return new StoreAuthority(actor.tenantNo(),actor.actorKey(),entity,storeNo,str(row,"name"),num(row,"version"));
    }
    public void requireConfigurationWritable(Actor actor,String management,String type,String no){configurationLookup(actor,management,type,no);if("BRAND".equals(type)){var brand=db.one("SELECT * FROM mch_brand WHERE tenant_no=? AND brand_no=? AND deleted=0",actor.tenantNo(),no);if("PREPARING".equals(str(brand,"stored_value_status")))throw BizException.of(ErrorCode.CONFLICT);}}
    public AuthorizedScope configurationLookup(Actor actor,String management,String type,String no){return scope(actor,management,type,no,"sv.issuer.configure",Set.of(),List.of());}
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


    /** Server-to-server maintenance evidence only; never expose this as a public scope query. */
    public void verifyMigrationSources(List<StoreAuthority> sources){
        if(sources==null||sources.isEmpty()||sources.size()>1000)throw BizException.of(ErrorCode.CONFLICT);
        var seen=new HashSet<List<String>>();
        for(var source:sources){
            if(!seen.add(List.of(source.tenantNo(),source.storeNo())))throw BizException.of(ErrorCode.CONFLICT);
            var row=db.one("SELECT s.entity_no,s.version FROM mch_store s JOIN mch_entity e ON e.tenant_no=s.tenant_no AND e.entity_no=s.entity_no WHERE s.tenant_no=? AND s.store_no=? AND s.status='ACTIVE' AND s.deleted=0 AND e.status='ACTIVE' AND e.deleted=0",source.tenantNo(),source.storeNo());
            if(row==null||!source.entityNo().equals(str(row,"entity_no"))||source.storeRevision()!=num(row,"version"))throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    public StoreAuthority prepareSettings(Actor actor,String management,String store){
        scopes.resolve(actor,management);scopes.require(actor,management,"SPACE",management,"management.grant.manage");scopes.require(actor,management,"STORE",store,"store.read");
        var source=storePreparationScope(actor,store);
        for(String permission:List.of("sv.issuer.read","sv.issuer.configure")){
            if(scopes.can(actor,management,"STORE",store,permission))continue;
            var prior=db.one("SELECT version,status FROM mch_management_grant WHERE tenant_no=? AND management_no=? AND actor_no=? AND resource_type='STORE' AND resource_no=? AND permission_code=?",actor.tenantNo(),management,actor.actorKey(),store,permission);
            scopes.grant(actor,management,new ai.neargo.shop.merchant.brand.ManagementModels.GrantCommand(actor.actorKey(),"STORE",store,permission,null,null,false,prior==null?null:num(prior,"version"),prior!=null));
        }
        return source;
    }
    public List<Map<String,Object>> settlementCandidates(Actor actor,String management,String type,String no){
        var base=scope(actor,management,type,no,"sv.issuer.configure",Set.of(),List.of());
        var rows=db.rows("SELECT entity_no,name FROM mch_entity WHERE tenant_no=? AND status='ACTIVE' AND deleted=0 ORDER BY entity_no LIMIT 1001",actor.tenantNo());
        if(rows.size()>1000)throw BizException.of(ErrorCode.CONFLICT);

        boolean admin=scopes.can(actor,management,"SPACE",management,"management.grant.manage");
        var owned=scopes.ownerAccounts(actor).stream().map(row->str(row,"entity_no")).collect(java.util.stream.Collectors.toSet());
        return rows.stream().filter(row->!"STORE".equals(type)||base.operatingEntityNo().equals(str(row,"entity_no")))
            .filter(row->scopes.can(actor,management,"ENTITY",str(row,"entity_no"),"sv.issuer.settlement.authorize")
                ||admin&&owned.contains(str(row,"entity_no"))&&scopes.can(actor,management,"ENTITY",str(row,"entity_no"),"entity.read"))
            .map(row->{String entity=str(row,"entity_no");boolean accepted=scopes.can(actor,management,"ENTITY",entity,"sv.issuer.settlement.authorize");
                return Map.<String,Object>of("entityNo",entity,"name",str(row,"name"),"accepted",accepted,"canAccept",!accepted&&admin&&owned.contains(entity));}).toList();

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

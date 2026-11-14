package ai.neargo.shop.merchant.brand.impl;

import static ai.neargo.shop.merchant.brand.ManagementRepository.num;
import static ai.neargo.shop.merchant.brand.ManagementRepository.str;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.brand.ManagementModels;
import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.merchant.brand.ManagementRepository;
import ai.neargo.shop.merchant.brand.ManagementScopeService;
import ai.neargo.shop.merchant.brand.StoredValuePolicyAuthorityService;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.ActivityAuthority;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.CatalogScope;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.PolicyAuthority;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.StoreScope;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.Target;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Live organization facts and exact resource permissions are resolved before a remote Pay call. */
@Service("storedValuePolicyAuthorityService")
public class StoredValuePolicyAuthorityServiceImpl implements StoredValuePolicyAuthorityService {
    private final ManagementRepository db;
    private final ManagementScopeService scopes;
    public StoredValuePolicyAuthorityServiceImpl(ManagementRepository db, ManagementScopeService scopes) {this.db=db;this.scopes=scopes;}
    public void prepareOwnPermissions(Actor actor,String management){
        scopes.resolve(actor,management);scopes.require(actor,management,"SPACE",management,"management.grant.manage");
        var targets=new ArrayList<String[]>();
        for(String permission:List.of("sv.policy.read","sv.policy.manage","sv.store.activity.approve"))targets.add(new String[]{"SPACE",management,permission});
        var brands=db.rows("SELECT brand_no FROM mch_brand WHERE tenant_no=? AND management_no=? AND deleted=0 LIMIT 1001",actor.tenantNo(),management);if(brands.size()>1000)throw BizException.of(ErrorCode.CONFLICT);
        for(var brand:brands)for(String permission:List.of("sv.policy.read","sv.policy.manage"))targets.add(new String[]{"BRAND",str(brand,"brand_no"),permission});
        for(var target:targets){
            if(!scopes.can(actor,management,target[0],target[1],target[2])||!scopes.canDelegate(actor,management,target[0],target[1],target[2]))continue;
            var prior=db.one("SELECT version,status FROM mch_management_grant WHERE tenant_no=? AND management_no=? AND actor_no=? AND resource_type=? AND resource_no=? AND permission_code=? AND deleted=0",actor.tenantNo(),management,actor.actorKey(),target[0],target[1],target[2]);
            if(prior!=null&&"ACTIVE".equals(str(prior,"status")))continue;
            scopes.grant(actor,management,new ManagementModels.GrantCommand(actor.actorKey(),target[0],target[1],target[2],null,null,true,prior==null?null:num(prior,"version"),prior!=null));
        }
    }
    public PolicyAuthority target(Actor actor,String management,String type,String no,String currency,String permission) {
        ContractChecks.require(Set.of("sv.policy.read","sv.policy.manage").contains(permission));
        ContractChecks.require(type!=null&&ai.neargo.shop.spi.storedvalue.StoredValuePolicies.TARGET_TYPES.contains(type)); ContractChecks.currency(currency);
        var space=scopes.resolve(actor,management);
        String resource="BRAND_STORES".equals(type)?"BRAND":type;
        scopes.require(actor,management,resource,no,permission);
        String brand=null;
        if("SPACE".equals(type)) {if(!management.equals(no))throw BizException.of(ErrorCode.FORBIDDEN);}
        else if("BRAND_STORES".equals(type)) {
            if(db.one("SELECT brand_no FROM mch_brand WHERE tenant_no=? AND management_no=? AND brand_no=? AND status='ACTIVE' AND deleted=0",actor.tenantNo(),management,no)==null)throw BizException.of(ErrorCode.NOT_FOUND);
        } else brand=storeBrand(actor,management,no);
        return new PolicyAuthority(actor.tenantNo(),actor.actorKey(),management,type,no,brand,currency,num(space,"authRevision"));
    }
    public StoreScope store(Actor actor,String management,String store,String currency) {
        scopes.resolve(actor,management); scopes.require(actor,management,"STORE",store,"sv.store.activity.read");
        return new StoreScope(actor.tenantNo(),management,store,storeBrand(actor,management,store),currency);
    }
    public CatalogScope catalog(Actor actor,String management,String type,String no,String currency) {
        scopes.resolve(actor,management); ContractChecks.currency(currency);
        if(type!=null||no!=null) {
            if(type==null||no==null)throw BizException.of(ErrorCode.BAD_REQUEST);
            target(actor,management,type,no,currency,"sv.policy.read");
            return new CatalogScope(actor.tenantNo(),actor.actorKey(),management,currency,List.of(new Target(type,no)));
        }
        var targets=new ArrayList<Target>();
        if(scopes.can(actor,management,"SPACE",management,"sv.policy.read"))targets.add(new Target("SPACE",management));
        var brands=db.rows("SELECT brand_no FROM mch_brand WHERE tenant_no=? AND management_no=? AND status='ACTIVE' AND deleted=0 LIMIT 1001",actor.tenantNo(),management);
        if(brands.size()>1000)throw BizException.of(ErrorCode.CONFLICT);
        for(var b:brands)if(scopes.can(actor,management,"BRAND",str(b,"brand_no"),"sv.policy.read"))targets.add(new Target("BRAND_STORES",str(b,"brand_no")));
        var stores=db.rows("SELECT DISTINCT resource_no FROM mch_management_grant WHERE tenant_no=? AND management_no=? AND actor_no=? AND resource_type='STORE' AND permission_code='sv.policy.read' AND status='ACTIVE' AND deleted=0 LIMIT 1001",actor.tenantNo(),management,actor.actorKey());
        if(stores.size()>1000)throw BizException.of(ErrorCode.CONFLICT);
        for(var store:stores)if(scopes.can(actor,management,"STORE",str(store,"resource_no"),"sv.policy.read")){
            storeBrand(actor,management,str(store,"resource_no"));targets.add(new Target("STORE",str(store,"resource_no")));
        }
        if(targets.size()>1000)throw BizException.of(ErrorCode.CONFLICT);
        return new CatalogScope(actor.tenantNo(),actor.actorKey(),management,currency,targets);
    }

    public List<PolicyTarget> policyTargets(Actor actor,String management){
        scopes.resolve(actor,management);var result=new ArrayList<PolicyTarget>();
        if(scopes.can(actor,management,"SPACE",management,"sv.policy.read")||scopes.can(actor,management,"SPACE",management,"sv.policy.manage"))result.add(new PolicyTarget("SPACE",management,str(scopes.space(actor,management),"name"),scopes.can(actor,management,"SPACE",management,"sv.policy.manage")));
        var brands=db.rows("SELECT brand_no,name FROM mch_brand WHERE tenant_no=? AND management_no=? AND status='ACTIVE' AND deleted=0 LIMIT 1001",actor.tenantNo(),management);
        if(brands.size()>1000)throw BizException.of(ErrorCode.CONFLICT);
        for(var brand:brands){String no=str(brand,"brand_no");boolean manage=scopes.can(actor,management,"BRAND",no,"sv.policy.manage");if(manage||scopes.can(actor,management,"BRAND",no,"sv.policy.read"))result.add(new PolicyTarget("BRAND_STORES",no,str(brand,"name"),manage));}
        var stores=db.rows("SELECT DISTINCT resource_no FROM mch_management_grant WHERE tenant_no=? AND management_no=? AND actor_no=? AND resource_type='STORE' AND permission_code IN ('sv.policy.read','sv.policy.manage') AND status='ACTIVE' AND deleted=0 LIMIT 1001",actor.tenantNo(),management,actor.actorKey());
        if(stores.size()>1000)throw BizException.of(ErrorCode.CONFLICT);
        for(var store:stores){String no=str(store,"resource_no");boolean manage=scopes.can(actor,management,"STORE",no,"sv.policy.manage");if(manage||scopes.can(actor,management,"STORE",no,"sv.policy.read")){storeBrand(actor,management,no);result.add(new PolicyTarget("STORE",no,str(db.one("SELECT name FROM mch_store WHERE tenant_no=? AND store_no=? AND deleted=0",actor.tenantNo(),no),"name"),manage));}}
        if(result.size()>1000)throw BizException.of(ErrorCode.CONFLICT);return List.copyOf(result);
    }
    public boolean canActivity(Actor actor,String management,String store,String permission){
        ContractChecks.require(Set.of("sv.store.activity.read","sv.store.activity.manage","sv.store.activity.publish").contains(permission));
        return scopes.can(actor,management,"STORE",store,permission);
    }
    public ActivityAuthority activity(Actor actor,String management,String store,String currency,String permission) {
        ContractChecks.require(Set.of("sv.store.activity.read","sv.store.activity.manage","sv.store.activity.publish").contains(permission));
        scopes.resolve(actor,management);scopes.require(actor,management,"STORE",store,permission);
        return activityFacts(actor,management,store,currency);
    }
    public boolean canApproval(Actor actor,String management){scopes.resolve(actor,management);return scopes.can(actor,management,"SPACE",management,"sv.store.activity.approve");}
    public void requireApproval(Actor actor,String management){scopes.resolve(actor,management);scopes.require(actor,management,"SPACE",management,"sv.store.activity.approve");}
    public ActivityAuthority approval(Actor actor,String management,String store,String currency){requireApproval(actor,management);return activityFacts(actor,management,store,currency);}
    private ActivityAuthority activityFacts(Actor actor,String management,String store,String currency){
        String brand=storeBrand(actor,management,store);var row=db.one("SELECT entity_no,version FROM mch_store WHERE tenant_no=? AND store_no=? AND status='ACTIVE' AND deleted=0",actor.tenantNo(),store);
        String principal=actor.actorKey();
        if(principal.startsWith("MCH:")){var account=db.one("SELECT user_no FROM mch_account WHERE tenant_no=? AND mch_account_no=? AND status='ACTIVE' AND deleted=0",actor.tenantNo(),principal.substring(4));if(account==null)throw BizException.of(ErrorCode.FORBIDDEN);if(str(account,"user_no")!=null)principal="USER:"+str(account,"user_no");}
        return new ActivityAuthority(new StoreScope(actor.tenantNo(),management,store,brand,currency,num(row,"version")),str(row,"entity_no"),actor.actorKey(),principal,num(scopes.space(actor,management),"auth_revision"));
    }
    public StoreScope currentStoreScope(StoreScope scope,String entity){
        if(db.one("SELECT management_no FROM mch_management_space WHERE tenant_no=? AND management_no=? AND status='ACTIVE' AND deleted=0",scope.tenantNo(),scope.managementNo())==null)return null;
        try {
            String brand=storeBrand(scope.tenantNo(),scope.managementNo(),scope.storeNo());
            var store=db.one("SELECT version FROM mch_store WHERE tenant_no=? AND store_no=? AND entity_no=? AND status='ACTIVE' AND deleted=0",scope.tenantNo(),scope.storeNo(),entity);
            return store==null?null:new StoreScope(scope.tenantNo(),scope.managementNo(),scope.storeNo(),brand,scope.currency(),num(store,"version"));
        }catch(BizException mismatch){if(mismatch.errorCode()==ErrorCode.FORBIDDEN||mismatch.errorCode()==ErrorCode.NOT_FOUND)return null;throw mismatch;}
    }
    private String storeBrand(Actor actor,String management,String store) {return storeBrand(actor.tenantNo(),management,store);}
    private String storeBrand(String tenant,String management,String store) {
        ContractChecks.id(store);
        if(db.one("SELECT s.store_no FROM mch_store s JOIN mch_entity e ON CAST(e.tenant_no AS BINARY(256))=CAST(s.tenant_no AS BINARY(256)) AND CAST(e.entity_no AS BINARY(256))=CAST(s.entity_no AS BINARY(256)) WHERE s.tenant_no=? AND s.store_no=? AND s.status='ACTIVE' AND s.deleted=0 AND e.status='ACTIVE' AND e.deleted=0",tenant,store)==null)throw BizException.of(ErrorCode.NOT_FOUND);
        var binding=db.one("SELECT b.brand_no,r.management_no FROM mch_brand_store_binding b JOIN mch_brand r ON CAST(r.tenant_no AS BINARY(256))=CAST(b.tenant_no AS BINARY(256)) AND CAST(r.brand_no AS BINARY(256))=CAST(b.brand_no AS BINARY(256)) WHERE b.tenant_no=? AND b.store_no=? AND b.status='ACTIVE' AND b.deleted=0 AND r.status='ACTIVE' AND r.deleted=0",tenant,store);
        if(binding==null)return null;
        if(!management.equals(str(binding,"management_no")))throw BizException.of(ErrorCode.FORBIDDEN);
        return str(binding,"brand_no");
    }
}

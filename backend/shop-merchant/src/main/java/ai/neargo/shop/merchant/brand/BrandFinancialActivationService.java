package ai.neargo.shop.merchant.brand;

import static ai.neargo.shop.merchant.brand.ManagementRepository.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValueBrandActivation.*;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Short Core transactions establish the organization fence before any Pay activation RPC. */
@Service
public class BrandFinancialActivationService {
    private final ManagementRepository db;private final ManagementScopeService scopes;private final StoredValueExecutionScopeService stores;
    public BrandFinancialActivationService(ManagementRepository db,ManagementScopeService scopes,StoredValueExecutionScopeService stores){this.db=db;this.scopes=scopes;this.stores=stores;}
    public record Pending(String activationNo,String state,Prepare command) { }
    public Pending original(Actor actor,String management,String brand,String key,long expectedRevision){
        ContractChecks.id(key);scopes.resolve(actor,management);scopes.require(actor,management,"BRAND",brand,"sv.issuer.configure");
        var saved=db.one("SELECT * FROM mch_brand_financial_activation WHERE tenant_no=? AND management_no=? AND brand_no=?",actor.tenantNo(),management,brand);if(saved==null)return null;
        var original=pending(saved);
        if(!key.equals(str(saved,"command_key"))||!actor.actorKey().equals(original.command().authority().actorNo())||expectedRevision!=original.command().issuer().revision())throw BizException.of(ErrorCode.CONFLICT);
        return original;
    }
    public Pending prepare(Actor actor,AuthorizedScope authority,View issuer,String key){
        ContractChecks.id(key);return db.write(()->{
            String management=authority.managementNo(),brand=authority.scopeNo();scopes.lock(actor,management);scopes.resolve(actor,management);scopes.require(actor,management,"BRAND",brand,"sv.issuer.configure");
            var row=db.one("SELECT * FROM mch_brand WHERE tenant_no=? AND management_no=? AND brand_no=? AND status='ACTIVE' AND deleted=0 FOR UPDATE",actor.tenantNo(),management,brand);if(row==null)throw BizException.of(ErrorCode.NOT_FOUND);
            if(!actor.actorKey().equals(authority.actorNo())||!actor.tenantNo().equals(authority.tenantNo())||!"BRAND".equals(authority.scopeType())||!brand.equals(issuer.scopeNo())||!authority.authorizedSettlementEntities().contains(issuer.settlementEntityNo())||authority.authorityRevision()!=num(scopes.space(actor,management),"auth_revision"))throw BizException.of(ErrorCode.FORBIDDEN);
            String hash=hash(db.encode(List.of(actor.actorKey(),management,brand,issuer.issuerNo(),issuer.issuerVersionNo(),issuer.revision())));
            var existing=db.one("SELECT * FROM mch_brand_financial_activation WHERE tenant_no=? AND brand_no=? FOR UPDATE",actor.tenantNo(),brand);
            if(existing!=null){if(!"ACTIVE".equals(str(existing,"status"))&&(!key.equals(str(existing,"command_key"))||!hash.equals(str(existing,"command_hash"))))throw BizException.of(ErrorCode.CONFLICT);return pending(existing);}
            if(db.one("SELECT change_no FROM mch_brand_change WHERE tenant_no=? AND status IN ('CHECKED','PREPARING') AND (from_brand_no=? OR to_brand_no=?) LIMIT 1",actor.tenantNo(),brand,brand)!=null)throw BizException.of(ErrorCode.CONFLICT);
            var members=db.rows("SELECT b.store_no FROM mch_brand_store_binding b JOIN mch_store s ON CAST(s.tenant_no AS BINARY(256))=CAST(b.tenant_no AS BINARY(256)) AND CAST(s.store_no AS BINARY(256))=CAST(b.store_no AS BINARY(256)) WHERE b.tenant_no=? AND b.brand_no=? AND b.status='ACTIVE' AND b.deleted=0 AND s.status='ACTIVE' AND s.deleted=0 ORDER BY b.store_no LIMIT 1001 FOR UPDATE",actor.tenantNo(),brand);if(members.size()>1000)throw BizException.of(ErrorCode.CONFLICT);
            var facts=new ArrayList<ai.neargo.shop.spi.storedvalue.StoredValueWallets.ExecutionScope>();for(var member:members){String no=str(member,"store_no");var store=db.one("SELECT entity_no FROM mch_store WHERE tenant_no=? AND store_no=? AND deleted=0",actor.tenantNo(),no);facts.add(stores.current(actor.tenantNo(),actor.actorKey(),str(store,"entity_no"),no,issuer.currency()));}
            String activation=BizKey.next(BizKey.BRAND_FINANCIAL_ACTIVATION);var command=new Prepare(activation,authority,issuer,num(row,"scope_revision"),facts);var saved=db.base(actor);saved.put("activation_no",activation);saved.put("management_no",management);saved.put("brand_no",brand);saved.put("issuer_no",issuer.issuerNo());saved.put("source_revision",command.sourceRevision());saved.put("command_key",key);saved.put("command_hash",hash);saved.put("source_snapshot",db.encode(command));saved.put("status","PREPARING");db.insert("mch_brand_financial_activation",saved);
            db.update("UPDATE mch_brand SET stored_value_status='PREPARING',updated_at=CURRENT_TIMESTAMP,updated_by=? WHERE tenant_no=? AND brand_no=?",actor.actorKey(),actor.tenantNo(),brand);
            return new Pending(activation,"PREPARING",command);
        });
    }
    public Map<String,Object> acknowledge(Actor actor,String management,String brand,Ack ack){return db.write(()->{
        scopes.lock(actor,management);scopes.resolve(actor,management);scopes.require(actor,management,"BRAND",brand,"sv.issuer.configure");var saved=db.one("SELECT * FROM mch_brand_financial_activation WHERE tenant_no=? AND management_no=? AND brand_no=? FOR UPDATE",actor.tenantNo(),management,brand);if(saved==null)throw BizException.of(ErrorCode.NOT_FOUND);var original=pending(saved).command();
        if(!ack.activationNo().equals(original.activationNo())||!ack.issuerNo().equals(original.issuer().issuerNo())||!ack.issuerVersionNo().equals(original.issuer().issuerVersionNo())||ack.sourceRevision()!=original.sourceRevision()||!"ISSUER_READY".equals(ack.state()))throw BizException.of(ErrorCode.CONFLICT);
        var current=db.one("SELECT scope_revision FROM mch_brand WHERE tenant_no=? AND brand_no=? AND deleted=0 FOR UPDATE",actor.tenantNo(),brand);if(num(current,"scope_revision")!=original.sourceRevision())throw BizException.of(ErrorCode.CONFLICT);
        db.update("UPDATE mch_brand_financial_activation SET status='ACTIVE',updated_at=CURRENT_TIMESTAMP,updated_by=?,version=version+1 WHERE tenant_no=? AND activation_no=? AND status='PREPARING'",actor.actorKey(),actor.tenantNo(),ack.activationNo());db.update("UPDATE mch_brand SET stored_value_status='ACTIVE',updated_at=CURRENT_TIMESTAMP,updated_by=? WHERE tenant_no=? AND brand_no=?",actor.actorKey(),actor.tenantNo(),brand);
        return Map.of("activationNo",ack.activationNo(),"state","ACTIVE","acceptedStores",ack.acceptedStores());
    });}
    private Pending pending(Map<String,Object> row){return new Pending(str(row,"activation_no"),str(row,"status"),db.decode(str(row,"source_snapshot"),Prepare.class));}
}

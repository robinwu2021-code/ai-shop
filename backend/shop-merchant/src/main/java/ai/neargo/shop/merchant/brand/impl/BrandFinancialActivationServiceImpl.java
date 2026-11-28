package ai.neargo.shop.merchant.brand.impl;

import ai.neargo.shop.merchant.brand.BrandFinancialActivationService;
import ai.neargo.shop.merchant.brand.ManagementRepository;
import ai.neargo.shop.merchant.brand.ManagementScopeService;
import ai.neargo.shop.merchant.brand.StoredValueExecutionScopeService;

import static ai.neargo.shop.merchant.brand.ManagementRepository.str;
import static ai.neargo.shop.merchant.brand.ManagementRepository.num;
import static ai.neargo.shop.merchant.brand.ManagementRepository.hash;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValueBrandActivation.Prepare;
import ai.neargo.shop.spi.storedvalue.StoredValueBrandActivation.Ack;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.AuthorizedScope;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.View;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.ExecutionScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Short Core transactions establish the organization fence before any Pay activation RPC.
 *
 * <p>平台基础库换表 G3（2026-11-28 用户定 A）：不再有开通记录表。栅栏 = {@code mch_brand.stored_value_status}
 * （NONE → PREPARING → ACTIVE）加 {@code scope_revision}——PREPARING 期间门店入退被挡住，成员事实不会变；
 * 开通号由租户、品牌、修订与发行方版本确定性生成，重试时按同一修订现算成员事实，得到同一条命令重发给支付侧。
 */
@Service("brandFinancialActivationService")
public class BrandFinancialActivationServiceImpl implements BrandFinancialActivationService {
    private final ManagementRepository db;private final ManagementScopeService scopes;private final StoredValueExecutionScopeService stores;
    public BrandFinancialActivationServiceImpl(ManagementRepository db,ManagementScopeService scopes,StoredValueExecutionScopeService stores){this.db=db;this.scopes=scopes;this.stores=stores;}

    /** 开通号：BFA + 摘要前 29 位（共 32 位）；同一品牌、同一修订、同一发行方版本永远得到同一个号 */
    static String activationNo(String tenant,String brand,long revision,String issuerNo,String issuerVersionNo){
        return "BFA"+hash(String.join("|",tenant,brand,Long.toString(revision),issuerNo,issuerVersionNo)).substring(0,29).toUpperCase(java.util.Locale.ROOT);
    }

    /** 已开通的直接返回结果；准备中的交给 {@link #prepare} 现算同一条命令重发（这里没有冻结的命令可取） */
    public Pending original(Actor actor,String management,String brand,String key,long expectedRevision){
        ContractChecks.id(key);scopes.resolve(actor,management);scopes.require(actor,management,"BRAND",brand,"sv.issuer.configure");
        var row=db.one("SELECT stored_value_status,scope_revision FROM mch_brand WHERE tenant_no=? AND management_no=? AND brand_no=? AND deleted=0",actor.tenantNo(),management,brand);
        if(row==null||!"ACTIVE".equals(str(row,"stored_value_status")))return null;
        return new Pending(null,"ACTIVE",null);
    }
    public Pending prepare(Actor actor,AuthorizedScope authority,View issuer,String key){
        ContractChecks.id(key);return db.write(()->{
            String management=authority.managementNo(),brand=authority.scopeNo();scopes.lock(actor,management);scopes.resolve(actor,management);scopes.require(actor,management,"BRAND",brand,"sv.issuer.configure");
            var row=db.one("SELECT * FROM mch_brand WHERE tenant_no=? AND management_no=? AND brand_no=? AND status='ACTIVE' AND deleted=0 FOR UPDATE",actor.tenantNo(),management,brand);if(row==null)throw BizException.of(ErrorCode.NOT_FOUND);
            if(!actor.actorKey().equals(authority.actorNo())||!actor.tenantNo().equals(authority.tenantNo())||!"BRAND".equals(authority.scopeType())||!brand.equals(issuer.scopeNo())||!authority.authorizedSettlementEntities().contains(issuer.settlementEntityNo())||authority.authorityRevision()!=num(scopes.space(actor,management),"auth_revision"))throw BizException.of(ErrorCode.FORBIDDEN);
            String state=str(row,"stored_value_status");long revision=num(row,"scope_revision");
            String activation=activationNo(actor.tenantNo(),brand,revision,issuer.issuerNo(),issuer.issuerVersionNo());
            if("ACTIVE".equals(state))return new Pending(activation,"ACTIVE",null);
            // 储值期间入退只在已开通后才有；普通入退当场生效，不会有在途
            var members=db.rows("SELECT b.store_no FROM mch_brand_store_binding b JOIN mch_store s ON CAST(s.tenant_no AS BINARY(256))=CAST(b.tenant_no AS BINARY(256)) AND CAST(s.store_no AS BINARY(256))=CAST(b.store_no AS BINARY(256)) WHERE b.tenant_no=? AND b.brand_no=? AND b.status='ACTIVE' AND b.deleted=0 AND s.status='ACTIVE' AND s.deleted=0 ORDER BY b.store_no LIMIT 1001 FOR UPDATE",actor.tenantNo(),brand);if(members.size()>1000)throw BizException.of(ErrorCode.CONFLICT);
            var facts=new ArrayList<ExecutionScope>();for(var member:members){String no=str(member,"store_no");var store=db.one("SELECT entity_no FROM mch_store WHERE tenant_no=? AND store_no=? AND deleted=0",actor.tenantNo(),no);facts.add(stores.current(actor.tenantNo(),actor.actorKey(),str(store,"entity_no"),no,issuer.currency()));}
            var command=new Prepare(activation,authority,issuer,revision,facts);
            if(!"PREPARING".equals(state))db.update("UPDATE mch_brand SET stored_value_status='PREPARING',updated_at=CURRENT_TIMESTAMP,updated_by=? WHERE tenant_no=? AND brand_no=?",actor.actorKey(),actor.tenantNo(),brand);
            return new Pending(activation,"PREPARING",command);
        });
    }
    public Map<String,Object> acknowledge(Actor actor,String management,String brand,Ack ack){return db.write(()->{
        scopes.lock(actor,management);scopes.resolve(actor,management);scopes.require(actor,management,"BRAND",brand,"sv.issuer.configure");
        var row=db.one("SELECT stored_value_status,scope_revision FROM mch_brand WHERE tenant_no=? AND management_no=? AND brand_no=? AND deleted=0 FOR UPDATE",actor.tenantNo(),management,brand);if(row==null)throw BizException.of(ErrorCode.NOT_FOUND);
        long revision=num(row,"scope_revision");
        // 回执必须对上「当前修订 + 回执里的发行方版本」现算出的开通号：修订变过、或回执拼错了，都对不上
        if(!"ISSUER_READY".equals(ack.state())||ack.sourceRevision()!=revision||!ack.activationNo().equals(activationNo(actor.tenantNo(),brand,revision,ack.issuerNo(),ack.issuerVersionNo())))throw BizException.of(ErrorCode.CONFLICT);
        String state=str(row,"stored_value_status");
        if("PREPARING".equals(state))db.update("UPDATE mch_brand SET stored_value_status='ACTIVE',updated_at=CURRENT_TIMESTAMP,updated_by=? WHERE tenant_no=? AND brand_no=?",actor.actorKey(),actor.tenantNo(),brand);
        else if(!"ACTIVE".equals(state))throw BizException.of(ErrorCode.CONFLICT);
        return Map.of("activationNo",ack.activationNo(),"state","ACTIVE","acceptedStores",ack.acceptedStores());
    });}
}

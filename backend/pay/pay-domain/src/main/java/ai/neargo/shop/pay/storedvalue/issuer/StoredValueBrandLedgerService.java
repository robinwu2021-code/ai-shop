package ai.neargo.shop.pay.storedvalue.issuer;

import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.service.EntitlementCommandDeduplicator;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuerRecharges.Funding;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/** Frozen brand promises recover independently of current policies or membership. No account merge or extra collection. */
@Service
public class StoredValueBrandLedgerService {
    public record Consumption(String voucherNo,long amountMinor) { }
    private final IssuerRepository db;private final StoredValueIssueQuotaService quotas;private final EntitlementCommandDeduplicator commands;
    public StoredValueBrandLedgerService(IssuerRepository db,StoredValueIssueQuotaService quotas,EntitlementCommandDeduplicator commands){this.db=db;this.quotas=quotas;this.commands=commands;}
    public boolean original(EntitlementContext ctx,String entity,String no){
        var row=db.one("SELECT * FROM sv_recharge WHERE tenant_no=? AND recharge_no=? AND deleted=0",ctx.tenantNo(),no);if(row==null||!"ISSUER_RELEASE".equals(text(row,"source_kind")))return false;
        transaction();if(!entity.equals(text(row,"entity_no")))throw BizException.of(ErrorCode.NOT_FOUND);var f=funding(row);
        var issuer=db.one("SELECT * FROM sv_issuer WHERE tenant_no=? AND issuer_no=? AND scope_type='BRAND' AND deleted=0 FOR UPDATE",ctx.tenantNo(),f.issuerNo());
        if(issuer==null||!"ISSUER".equals(text(issuer,"write_mode"))||!Set.of("ACTIVE","ISSUE_STOPPED").contains(text(issuer,"status")))conflict();
        var version=db.one("SELECT settlement_entity_no,currency FROM sv_issuer_version WHERE tenant_no=? AND issuer_no=? AND issuer_version_no=? AND deleted=0",ctx.tenantNo(),f.issuerNo(),f.issuerVersionNo());
        if(version==null||!f.settlementEntityNo().equals(text(version,"settlement_entity_no"))||!f.currency().equals(text(version,"currency"))||!f.issuerNo().equals(text(row,"issuer_no"))||!f.issuerVersionNo().equals(text(row,"issuer_version_no"))||!f.releaseNo().equals(text(row,"release_no"))||!f.tierNo().equals(text(row,"tier_no"))||!f.collectionEntityNo().equals(text(row,"collection_entity_no"))||!f.settlementEntityNo().equals(text(row,"settlement_entity_no"))||!f.customerNo().equals(text(row,"customer_no"))||!Objects.equals(f.userNo(),text(row,"user_no"))||!f.operatingEntityNo().equals(text(row,"entity_no"))||!f.storeNo().equals(text(row,"store_no"))||!f.currency().equals(text(row,"currency"))||f.paidAmountMinor()!=number(row,"principal_minor")||f.bonusAmountMinor()!=number(row,"bonus_minor"))conflict();
        quotas.lockForOriginal(ctx,no);return true;
    }
    public void credited(EntitlementContext ctx,String entity,String rechargeNo,String accountNo,String lotNo,String voucherNo){
        transaction();var row=db.one("SELECT * FROM sv_recharge WHERE tenant_no=? AND recharge_no=? AND deleted=0",ctx.tenantNo(),rechargeNo);if(row==null||!"ISSUER_RELEASE".equals(text(row,"source_kind")))conflict();var f=funding(row);
        var account=db.one("SELECT * FROM sv_account WHERE tenant_no=? AND account_no=? AND deleted=0",ctx.tenantNo(),accountNo);var issuer=db.one("SELECT scope_no FROM sv_issuer WHERE tenant_no=? AND issuer_no=? AND scope_type='BRAND' AND deleted=0",ctx.tenantNo(),f.issuerNo());
        if(!"CREDITED".equals(text(row,"status"))||!entity.equals(f.operatingEntityNo())||account==null||!f.issuerNo().equals(text(account,"issuer_no"))||!f.customerNo().equals(text(account,"customer_no"))||!f.settlementEntityNo().equals(text(account,"entity_no"))||text(account,"store_no")!=null||issuer==null||text(row,"quota_reservation_no")==null)conflict();
        changed(db.update("UPDATE sv_lot SET issuer_no=?,issuer_version_no=?,release_no=?,tier_no=?,collection_entity_no=?,settlement_entity_no=? WHERE tenant_no=? AND lot_no=? AND recharge_no=?",f.issuerNo(),f.issuerVersionNo(),f.releaseNo(),f.tierNo(),f.collectionEntityNo(),f.settlementEntityNo(),ctx.tenantNo(),lotNo,rechargeNo));
        changed(db.update("UPDATE sv_voucher SET issuer_no=? WHERE tenant_no=? AND voucher_no=? AND account_no=?",f.issuerNo(),ctx.tenantNo(),voucherNo,accountNo));
        changed(db.update("UPDATE sv_statement SET issuer_no=?,scope_type='BRAND',scope_no=?,release_no=?,operating_entity_no=?,settlement_entity_no=? WHERE tenant_no=? AND voucher_no=?",f.issuerNo(),text(issuer,"scope_no"),f.releaseNo(),f.operatingEntityNo(),f.settlementEntityNo(),ctx.tenantNo(),voucherNo));
        var at=Timestamp.from(Instant.now());var index=db.one("SELECT * FROM sv_customer_issuer_index WHERE tenant_no=? AND account_no=? FOR UPDATE",ctx.tenantNo(),accountNo);
        if(index==null){var value=base(ctx,at);value.put("customer_no",f.customerNo());value.put("issuer_no",f.issuerNo());value.put("account_no",accountNo);value.put("currency",f.currency());value.put("has_recharge",1);value.put("last_recharge_at",row.get("credited_at"));value.put("last_activity_at",row.get("credited_at"));value.put("last_occurrence_store_no",f.storeNo());db.insert("sv_customer_issuer_index",value);}
        else{if(!f.customerNo().equals(text(index,"customer_no"))||!f.issuerNo().equals(text(index,"issuer_no")))conflict();db.update("UPDATE sv_customer_issuer_index SET has_recharge=1,last_recharge_at=?,last_activity_at=?,last_occurrence_store_no=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND account_no=?",row.get("credited_at"),row.get("credited_at"),f.storeNo(),at,ctx.actorNo(),ctx.tenantNo(),accountNo);}
        quotas.commit(ctx,rechargeNo);
    }
    public void consumed(EntitlementContext ctx,String entity,String accountNo,String voucherNo){
        transaction();var account=db.one("SELECT * FROM sv_account WHERE tenant_no=? AND account_no=? AND deleted=0",ctx.tenantNo(),accountNo);if(account==null||text(account,"issuer_no")==null)conflict();
        var issuer=db.one("SELECT issuer_no FROM sv_issuer WHERE tenant_no=? AND issuer_no=? AND scope_type='BRAND' AND deleted=0",ctx.tenantNo(),text(account,"issuer_no"));var voucher=db.one("SELECT * FROM sv_voucher WHERE tenant_no=? AND voucher_no=? AND account_no=? AND biz_type='CONSUME' AND deleted=0",ctx.tenantNo(),voucherNo,accountNo);
        if(issuer==null||voucher==null||number(voucher,"principal_delta")>0||number(voucher,"bonus_delta")>0)conflict();long amount=Math.negateExact(Math.addExact(number(voucher,"principal_delta"),number(voucher,"bonus_delta")));if(amount<=0)conflict();
        commands.execute(new CatalogScope(ctx.tenantNo(),entity,ctx.actorNo(),false),"SV_BRAND_CONSUME",voucherNo,List.of(accountNo,text(account,"issuer_no"),amount),voucherNo,Consumption.class,()->{var quota=quotas.lockIssuerQuota(ctx,text(account,"issuer_no"),text(account,"currency"));if(number(quota,"outstanding_minor")<amount)conflict();changed(db.update("UPDATE sv_issue_quota SET outstanding_minor=outstanding_minor-?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND quota_no=? AND outstanding_minor>=?",amount,Timestamp.from(Instant.now()),ctx.actorNo(),ctx.tenantNo(),text(quota,"quota_no"),amount));return new Consumption(voucherNo,amount);});
    }
    private Funding funding(Map<String,Object> row){return db.decode(text(row,"permit_snapshot"),Funding.class);}
    private Map<String,Object> base(EntitlementContext ctx,Timestamp at){var row=new LinkedHashMap<String,Object>();row.put("tenant_no",ctx.tenantNo());row.put("version",0);row.put("deleted",0);row.put("created_at",at);row.put("updated_at",at);row.put("created_by",ctx.actorNo());row.put("updated_by",ctx.actorNo());return row;}
    private void transaction(){if(!db.payTransactionActive())throw new IllegalStateException("Pay transaction required");}
    private static void changed(int n){if(n!=1)conflict();}
    private static void conflict(){throw BizException.of(ErrorCode.CONFLICT);}
}

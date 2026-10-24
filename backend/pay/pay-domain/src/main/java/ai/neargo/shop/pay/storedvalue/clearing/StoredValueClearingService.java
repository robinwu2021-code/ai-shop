package ai.neargo.shop.pay.storedvalue.clearing;

import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Immutable per-allocation clearing is committed with its debit. This class never registers or confirms payment. */
@Service
public class StoredValueClearingService {
    private final IssuerRepository db;private final boolean enabled;
    public StoredValueClearingService(IssuerRepository db,@Value("${shop.pay.stored-value.brand-execution-enabled:false}")boolean enabled){this.db=db;this.enabled=enabled;}
    public void record(EntitlementContext ctx,String usageNo,String voucherNo){
        if(!db.payTransactionActive())throw new IllegalStateException("Pay transaction required");
        var usage=db.one("SELECT * FROM ent_usage WHERE tenant_no=? AND usage_no=? AND asset_kind='STORED_VALUE' AND status='COMMITTED' AND deleted=0",ctx.tenantNo(),usageNo);
        var voucher=db.one("SELECT * FROM sv_voucher WHERE tenant_no=? AND voucher_no=? AND biz_type='CONSUME' AND deleted=0",ctx.tenantNo(),voucherNo);
        if(usage==null||voucher==null||!text(usage,"operation_no").equals(text(voucher,"biz_no")))conflict();
        var account=db.one("SELECT * FROM sv_account WHERE tenant_no=? AND account_no=? AND deleted=0",ctx.tenantNo(),text(voucher,"account_no"));
        if(account==null)conflict();if(text(account,"issuer_no")==null)return;
        var issuer=db.one("SELECT * FROM sv_issuer WHERE tenant_no=? AND issuer_no=? AND deleted=0",ctx.tenantNo(),text(account,"issuer_no"));
        if(issuer==null)conflict();if(!enabled&&"STORE".equals(text(issuer,"scope_type")))return;
        var parts=db.rows("SELECT a.*,l.recharge_no,l.recharge_store_no,l.issuer_no AS lot_issuer_no,l.issuer_version_no,l.release_no AS lot_release_no,l.account_no FROM sv_hold_allocation a JOIN sv_lot l ON CAST(l.tenant_no AS BINARY(256))=CAST(a.tenant_no AS BINARY(256)) AND CAST(l.lot_no AS BINARY(256))=CAST(a.lot_no AS BINARY(256)) WHERE a.tenant_no=? AND a.operation_no=? AND a.deleted=0 AND l.deleted=0 ORDER BY a.id",ctx.tenantNo(),text(usage,"operation_no"));
        if(parts.isEmpty())conflict();long principal=0,bonus=0;boolean created=false;
        for(var part:parts){
            if(!text(account,"account_no").equals(text(part,"account_no"))||!text(account,"issuer_no").equals(text(part,"lot_issuer_no"))||!text(account,"issuer_no").equals(text(part,"issuer_no"))||!Objects.equals(text(part,"release_no"),text(part,"lot_release_no"))||text(part,"allocation_no")==null)conflict();
            var entry=db.one("SELECT * FROM sv_entry WHERE tenant_no=? AND voucher_no=? AND lot_no=? AND deleted=0",ctx.tenantNo(),voucherNo,text(part,"lot_no"));
            long p=number(part,"principal_minor"),b=number(part,"bonus_minor");
            if(p<0||b<0||Math.addExact(p,b)<=0||entry==null||number(entry,"principal_delta")!=-p||number(entry,"bonus_delta")!=-b)conflict();
            principal=Math.addExact(principal,p);bonus=Math.addExact(bonus,b);
            var version=db.one("SELECT * FROM sv_issuer_version WHERE tenant_no=? AND issuer_no=? AND issuer_version_no=? AND deleted=0",ctx.tenantNo(),text(account,"issuer_no"),text(part,"issuer_version_no"));
            if(version==null||!text(account,"currency").equals(text(version,"currency")))conflict();
            String payer=text(version,"settlement_entity_no"),payee=text(usage,"entity_no"),mode=text(version,"clearing_mode");
            if(!Set.of("PRINCIPAL_COMPONENT","INTERNAL_RECON").contains(mode)||!payer.equals(payee)&&!"PRINCIPAL_COMPONENT".equals(mode))conflict();
            long payable=payer.equals(payee)?0:p;
            String key=EntitlementRecords.digest(db.encode(List.of("USE",ctx.tenantNo(),usageNo,text(part,"allocation_no"))));var at=Timestamp.from(instant(voucher.get("occurred_at")));
            var row=new LinkedHashMap<String,Object>();row.put("tenant_no",ctx.tenantNo());row.put("issuer_no",text(account,"issuer_no"));row.put("usage_no",usageNo);row.put("allocation_no",text(part,"allocation_no"));row.put("lot_no",text(part,"lot_no"));row.put("release_no",text(part,"lot_release_no"));row.put("issuer_version_no",text(part,"issuer_version_no"));row.put("recharge_store_no",text(part,"recharge_store_no"));row.put("redemption_store_no",text(usage,"store_no"));row.put("payer_entity_no",payer);row.put("payee_entity_no",payee);row.put("currency",text(account,"currency"));row.put("principal_used_minor",p);row.put("bonus_used_minor",b);row.put("payable_minor",payable);row.put("issuer_borne_minor",b);row.put("entry_type","USE");row.put("occurred_at",at);row.put("source_event_key",key);
            String hash=EntitlementRecords.digest(db.encode(row));var existing=db.one("SELECT content_hash FROM sv_clearing_entry WHERE tenant_no=? AND source_event_key=?",ctx.tenantNo(),key);
            if(existing!=null){if(!hash.equals(text(existing,"content_hash")))conflict();continue;}
            created=true;row.put("content_hash",hash);row.put("clearing_no",BizKey.next(BizKey.STORED_VALUE_CLEARING));row.put("created_at",at);row.put("updated_at",at);row.put("created_by",ctx.actorNo());row.put("updated_by",ctx.actorNo());db.insert("sv_clearing_entry",row);
        }
        if(number(voucher,"principal_delta")!=-principal||number(voucher,"bonus_delta")!=-bonus||number(usage,"amount_minor")!=Math.addExact(principal,bonus))conflict();
        if(!created)return;
        db.update("UPDATE sv_voucher SET issuer_no=? WHERE tenant_no=? AND voucher_no=?",text(account,"issuer_no"),ctx.tenantNo(),voucherNo);
        db.update("UPDATE sv_statement SET issuer_no=?,scope_type=?,scope_no=?,operating_entity_no=?,settlement_entity_no=? WHERE tenant_no=? AND voucher_no=?",text(account,"issuer_no"),text(issuer,"scope_type"),text(issuer,"scope_no"),text(usage,"entity_no"),text(account,"entity_no"),ctx.tenantNo(),voucherNo);
        db.update("UPDATE sv_customer_issuer_index SET last_activity_at=?,last_occurrence_store_no=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND account_no=?",voucher.get("occurred_at"),text(usage,"store_no"),Timestamp.from(Instant.now()),ctx.actorNo(),ctx.tenantNo(),text(account,"account_no"));
    }
    private static Instant instant(Object value){if(value instanceof Timestamp time)return time.toInstant();if(value instanceof LocalDateTime time)return time.toInstant(ZoneOffset.UTC);if(value instanceof Instant time)return time;throw new IllegalStateException("Missing Pay timestamp");}
    private static void conflict(){throw BizException.of(ErrorCode.CONFLICT);}
}

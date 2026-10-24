package ai.neargo.shop.pay.storedvalue.issuer.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import ai.neargo.shop.pay.storedvalue.issuer.StoredValueIssuerAccountService;
import ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.text;
import tools.jackson.databind.ObjectMapper;



/** Exact issuer/customer wallet selection. Never merges, binds identities or rewrites an existing wallet. */
@Service("storedValueIssuerAccountService")
public class StoredValueIssuerAccountServiceImpl implements StoredValueIssuerAccountService {
    private record Identity(String customer,String user) { }
    private final IssuerRepository db;private final ObjectMapper json;private final boolean enabled;
    public StoredValueIssuerAccountServiceImpl(IssuerRepository db,ObjectMapper json,@Value("${shop.pay.stored-value.issuer-mode:false}") boolean enabled){this.db=db;this.json=json;this.enabled=enabled;}
    public Lookup credit(EntitlementContext ctx,String rechargeNo) {
        if(!enabled)return new Lookup(false,null);
        transaction();
        var original=db.one("SELECT * FROM sv_recharge WHERE tenant_no=? AND recharge_no=? AND deleted=0",ctx.tenantNo(),rechargeNo);
        if(original==null)throw BizException.of(ErrorCode.NOT_FOUND);
        String entity=text(original,"entity_no"),store=text(original,"store_no"),currency=text(original,"currency"),no=text(original,"issuer_no");
        var issuer=storeIssuer(ctx.tenantNo(),store,true);
        if(no==null) {
            if(issuer!=null&&!Set.of("LEGACY","LEGACY_MAPPED").contains(text(issuer,"write_mode")))conflict();
            return new Lookup(false,null);
        }
        if(issuer==null||!no.equals(text(issuer,"issuer_no"))||!Set.of("ISSUER","LEGACY_MAPPED").contains(text(issuer,"write_mode")))conflict();
        boolean replay="CREDITED".equals(text(original,"status"));
        if(!replay&&(!"ISSUER".equals(text(issuer,"write_mode"))||!Set.of("ACTIVE","ISSUE_STOPPED").contains(text(issuer,"status"))))conflict();
        if(!replay&&!"RECEIVED".equals(text(original,"status")))conflict();
        var version=db.one("SELECT currency,settlement_entity_no FROM sv_issuer_version WHERE tenant_no=? AND issuer_no=? AND issuer_version_no=? AND deleted=0",ctx.tenantNo(),no,text(original,"issuer_version_no"));
        if(version==null||!entity.equals(text(version,"settlement_entity_no"))||!currency.equals(text(version,"currency"))
                ||!entity.equals(text(original,"collection_entity_no"))||!entity.equals(text(original,"settlement_entity_no")))conflict();
        String identity=text(original,"customer_no")==null?text(original,"user_no"):text(original,"customer_no");
        var person=resolve(ctx.tenantNo(),identity,text(original,"user_no"));
        var account=wallet(ctx.tenantNo(),no,person.customer(),currency,true);
        rejectShadows(ctx.tenantNo(),entity,store,currency,identity,person,account,true);
        if(account==null) {
            if(replay)conflict();
            String accountNo=StoredValueFacts.no("SA",List.of(ctx.tenantNo(),no,person.customer(),currency),json);
            var now=Timestamp.from(Instant.now());
            if(db.update("INSERT INTO sv_account(tenant_no,account_no,user_no,customer_no,entity_no,store_no,currency,status,issuer_no,issuer_version_no,principal_minor,bonus_minor,held_principal_minor,held_bonus_minor,account_seq,version,deleted,created_at,updated_at,created_by,updated_by) VALUES(?,?,?,?,?,?,?,'ACTIVE',?,?,0,0,0,0,0,0,0,?,?,?,?)",ctx.tenantNo(),accountNo,person.user(),person.customer(),entity,store,currency,no,text(original,"issuer_version_no"),now,now,ctx.actorNo(),ctx.actorNo())!=1)conflict();
            account=wallet(ctx.tenantNo(),no,person.customer(),currency,true);
        }
        verify(account,person,entity,store);
        return new Lookup(true,text(account,"account_no"));
    }
    public Lookup lookup(String tenant,String entity,String store,String currency,String identity,boolean lock) {
        if(!enabled)return new Lookup(false,null);
        if(lock)transaction();
        var issuer=storeIssuer(tenant,store,lock);
        if(issuer==null||Set.of("LEGACY").contains(text(issuer,"write_mode")))return new Lookup(false,null);
        String mode=text(issuer,"write_mode");
        if(!Set.of("ISSUER","LEGACY_MAPPED","FENCED").contains(mode)||lock&&"FENCED".equals(mode))conflict();
        var person=resolve(tenant,identity,null);
        var account=wallet(tenant,text(issuer,"issuer_no"),person.customer(),currency,lock);
        // Before metadata mapping the original read path remains visible during maintenance.
        if(account==null&&"FENCED".equals(mode))return new Lookup(false,null);
        rejectShadows(tenant,entity,store,currency,identity,person,account,lock);
        if(account!=null)verify(account,person,entity,store);
        return new Lookup(true,text(account,"account_no"));
    }
    private Identity resolve(String tenant,String identity,String expectedUser) {
        ContractChecks.id(identity);
        var links=db.rows("SELECT customer_no,user_no FROM sv_customer_identity WHERE tenant_no=? AND (customer_no=? OR user_no=? OR user_no=?)",tenant,identity,identity,expectedUser);
        if(links.isEmpty()) {
            if(expectedUser!=null&&!expectedUser.equals(identity))conflict();
            return new Identity(identity,expectedUser);
        }
        if(links.size()!=1)conflict();
        String customer=text(links.getFirst(),"customer_no"),user=text(links.getFirst(),"user_no");
        ContractChecks.id(customer);ContractChecks.id(user);
        if(!identity.equals(customer)&&!identity.equals(user)||expectedUser!=null&&!expectedUser.equals(user))conflict();
        return new Identity(customer,user);
    }
    private Map<String,Object> storeIssuer(String tenant,String store,boolean lock){
        return db.one("SELECT issuer_no,write_mode,status FROM sv_issuer WHERE tenant_no=? AND scope_type='STORE' AND scope_no=? AND deleted=0"+(lock?" FOR UPDATE":""),tenant,store);
    }
    private Map<String,Object> wallet(String tenant,String issuer,String customer,String currency,boolean lock){
        var rows=db.rows("SELECT a.account_no,a.customer_no,a.user_no,a.entity_no,a.store_no FROM sv_account a JOIN sv_issuer_version v ON v.tenant_no=a.tenant_no AND v.issuer_no=a.issuer_no AND v.issuer_version_no=a.issuer_version_no AND v.currency=a.currency AND v.settlement_entity_no=a.entity_no AND v.deleted=0 WHERE a.tenant_no=? AND a.issuer_no=? AND a.customer_no=? AND a.currency=? AND a.deleted=0"+(lock?" FOR UPDATE":""),tenant,issuer,customer,currency);
        if(rows.size()>1)conflict();return rows.isEmpty()?null:rows.getFirst();
    }
    private void rejectShadows(String tenant,String entity,String store,String currency,String identity,Identity person,Map<String,Object> account,boolean lock){
        var rows=db.rows("SELECT account_no FROM sv_account WHERE tenant_no=? AND entity_no=? AND (store_no=? OR store_no IS NULL) AND currency=? AND (customer_no=? OR customer_no=? OR user_no=?) AND deleted=0"+(lock?" FOR UPDATE":""),tenant,entity,store,currency,person.customer(),identity,person.user());
        for(var row:rows)if(account==null||!text(account,"account_no").equals(text(row,"account_no")))conflict();
    }
    private void verify(Map<String,Object> account,Identity person,String entity,String store){
        if(account==null||!entity.equals(text(account,"entity_no"))||!store.equals(text(account,"store_no"))||!person.customer().equals(text(account,"customer_no"))
                ||text(account,"user_no")!=null&&person.user()!=null&&!person.user().equals(text(account,"user_no")))conflict();
    }
    private static void transaction(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Pay transaction required");}
    private static void conflict(){throw BizException.of(ErrorCode.CONFLICT);}
}

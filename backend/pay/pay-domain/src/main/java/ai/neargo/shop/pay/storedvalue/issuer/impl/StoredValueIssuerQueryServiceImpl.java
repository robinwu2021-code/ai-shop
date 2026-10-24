package ai.neargo.shop.pay.storedvalue.issuer.impl;

import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.number;
import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.text;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import ai.neargo.shop.pay.storedvalue.issuer.StoredValueIssuerQueryService;
import ai.neargo.shop.pay.storedvalue.service.StoredValueCursorCodec;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.AvailableAccount;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.Balance;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.ExecutionScope;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.Query;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.Selected;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Read-only issuer wallets. No fallback merging, account creation or amount aggregation across issuers. */
@Service("storedValueIssuerQueryService")
public class StoredValueIssuerQueryServiceImpl implements StoredValueIssuerQueryService {
    private final IssuerRepository db;private final StoredValueCursorCodec refs;private final boolean enabled;private final boolean brandEnabled;private final boolean crossEntityEnabled;
    public StoredValueIssuerQueryServiceImpl(IssuerRepository db,StoredValueCursorCodec refs,
            @Value("${shop.pay.stored-value.issuer-mode:false}")boolean enabled,
            @Value("${shop.pay.stored-value.brand-execution-enabled:false}")boolean brandEnabled,
            @Value("${shop.pay.stored-value.cross-entity-enabled:false}")boolean crossEntityEnabled){this.db=db;this.refs=refs;this.enabled=enabled;this.brandEnabled=brandEnabled;this.crossEntityEnabled=crossEntityEnabled;}
    public List<AvailableAccount> accounts(Query query){
        requireEnabled();var scope=query.scope();var result=new ArrayList<AvailableAccount>();
        for(var issuer:issuers(scope))result.add(option(query,issuer));return List.copyOf(result);
    }
    public ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreOptions storeOptions(ExecutionScope scope){
        requireEnabled();
        return new ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreOptions(scope.storeNo(),false,issuers(scope).stream().map(i->
            new ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreOption(text(i,"issuer_no"),text(i,"scope_type"),text(i,"scope_no"),text(i,"name"),text(i,"currency"),text(i,"status"),text(i,"write_mode"),unavailable(scope,i,true)==null,unavailable(scope,i,false)==null,unavailable(scope,i,true))).toList());
    }
    public Selected select(Query query,String issuerRef,String accountRef){return selection(query,issuerRef,accountRef,true);}
    public Selected readSelection(Query query,String issuerRef,String accountRef){return selection(query,issuerRef,accountRef,false);}
    private Selected selection(Query query,String issuerRef,String accountRef,boolean forUse){
        requireEnabled();ContractChecks.require(issuerRef!=null&&accountRef!=null);
        var issuerToken=refs.decode(issuerRef,"SV_WALLET_ISSUER",query.scope().tenantNo(),query.scope().actorNo(),filter(query));
        var accountToken=refs.decode(accountRef,"SV_WALLET_ACCOUNT",query.scope().tenantNo(),query.scope().actorNo(),filter(query));
        if(issuerToken==null||accountToken==null)notFound();
        var matches=issuers(query.scope()).stream().filter(i->issuerToken.sortKey().equals(text(i,"issuer_no"))).toList();
        if(matches.size()!=1)notFound();var issuer=matches.getFirst();var account=wallet(query,issuer);
        if(account==null||!accountToken.sortKey().equals(text(account,"account_no"))||forUse&&!"ACTIVE".equals(text(account,"status")))notFound();
        if(forUse&&unavailable(query.scope(),issuer,false)!=null)throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        return new Selected(text(issuer,"issuer_no"),text(issuer,"current_issuer_version_no"),text(issuer,"scope_type"),text(issuer,"scope_no"),text(account,"account_no"),query.customer().customerNo(),query.scope().currency(),number(account,"version"),text(issuer,"name"));
    }
    public CursorPage<ai.neargo.shop.spi.storedvalue.StoredValueIssuerRecharges.FundedCustomer> funded(ExecutionScope scope,String cursor,int size){
        requireEnabled();ContractChecks.require(size>0&&size<=100);
        String filter=EntitlementRecords.digest(db.encode(Arrays.asList(scope.tenantNo(),scope.actorNo(),scope.entityNo(),scope.storeNo(),scope.storeRevision(),scope.brandNo(),scope.brandBindingNo(),scope.brandBindingRevision(),scope.currency())));
        var after=refs.decode(cursor,"SV_FUNDED_CUSTOMERS",scope.tenantNo(),scope.actorNo(),filter);
        var args=new ArrayList<Object>(Arrays.asList(scope.tenantNo(),scope.currency(),scope.storeNo(),scope.entityNo(),scope.brandNo()));
        String having="";if(after!=null){having=" HAVING (MAX(r.credited_at)<? OR (MAX(r.credited_at)=? AND a.customer_no<?))";var at=java.sql.Timestamp.from(Instant.ofEpochMilli(after.occurredAtMillis()));args.add(at);args.add(at);args.add(after.sortKey());}args.add(size+1);
        var rows=db.rows("SELECT a.customer_no,MAX(r.credited_at) AS last_recharge_at FROM sv_account a JOIN sv_lot l ON CAST(l.tenant_no AS BINARY(256))=CAST(a.tenant_no AS BINARY(256)) AND CAST(l.account_no AS BINARY(256))=CAST(a.account_no AS BINARY(256)) AND l.deleted=0 JOIN sv_recharge r ON CAST(r.tenant_no AS BINARY(256))=CAST(l.tenant_no AS BINARY(256)) AND CAST(r.recharge_no AS BINARY(256))=CAST(l.recharge_no AS BINARY(256)) AND r.status='CREDITED' AND r.deleted=0 LEFT JOIN sv_issuer i ON CAST(i.tenant_no AS BINARY(256))=CAST(a.tenant_no AS BINARY(256)) AND CAST(i.issuer_no AS BINARY(256))=CAST(a.issuer_no AS BINARY(256)) AND i.deleted=0 WHERE a.tenant_no=? AND a.currency=? AND a.deleted=0 AND a.customer_no IS NOT NULL AND ((a.store_no=? AND a.entity_no=? AND (i.issuer_no IS NULL OR i.scope_type='STORE')) OR (i.scope_type='BRAND' AND i.scope_no=? AND a.store_no IS NULL)) GROUP BY a.customer_no"+having+" ORDER BY last_recharge_at DESC,a.customer_no DESC LIMIT ?",args.toArray());
        boolean more=rows.size()>size;var shown=rows.subList(0,Math.min(rows.size(),size));String next=null;
        if(more){var last=shown.getLast();next=refs.encode("SV_FUNDED_CUSTOMERS",scope.tenantNo(),scope.actorNo(),filter,instant(last.get("last_recharge_at")).toEpochMilli(),text(last,"customer_no"));}
        return new CursorPage<>(shown.stream().map(r->new ai.neargo.shop.spi.storedvalue.StoredValueIssuerRecharges.FundedCustomer(text(r,"customer_no"),instant(r.get("last_recharge_at")))).toList(),next);
    }
    private AvailableAccount option(Query query,Map<String,Object> issuer){
        var account=wallet(query,issuer);var s=query.scope();String no=text(issuer,"issuer_no");String filters=filter(query);long at=Instant.now().toEpochMilli();
        String issuerRef=refs.encode("SV_WALLET_ISSUER",s.tenantNo(),s.actorNo(),filters,at,no);
        String accountRef=account==null?null:refs.encode("SV_WALLET_ACCOUNT",s.tenantNo(),s.actorNo(),filters,at,text(account,"account_no"));
        Balance balance=null;boolean active=account!=null&&"ACTIVE".equals(text(account,"status"));
        if(account!=null){long amount=Math.addExact(number(account,"principal_minor"),number(account,"bonus_minor"));long held=Math.addExact(number(account,"held_principal_minor"),number(account,"held_bonus_minor"));balance=new Balance(amount,held,Math.subtractExact(amount,held));}
        String reason=unavailable(s,issuer,false);
        return new AvailableAccount(text(issuer,"scope_type"),issuerRef,text(issuer,"name"),s.currency(),accountRef,balance,unavailable(s,issuer,true)==null,active&&reason==null,reason==null&&!active?(account==null?"NO_ACCOUNT":"ACCOUNT_FROZEN"):reason);
    }
    private List<Map<String,Object>> issuers(ExecutionScope s){
        return db.rows("SELECT i.*,v.settlement_entity_no,v.currency FROM sv_issuer i JOIN sv_issuer_version v ON CAST(v.tenant_no AS BINARY(256))=CAST(i.tenant_no AS BINARY(256)) AND CAST(v.issuer_no AS BINARY(256))=CAST(i.issuer_no AS BINARY(256)) AND CAST(v.issuer_version_no AS BINARY(256))=CAST(i.current_issuer_version_no AS BINARY(256)) WHERE i.tenant_no=? AND i.deleted=0 AND v.deleted=0 AND v.currency=? AND ((i.scope_type='STORE' AND i.scope_no=?) OR (i.scope_type='BRAND' AND i.scope_no=?)) ORDER BY CASE WHEN i.scope_type='STORE' THEN 0 ELSE 1 END",s.tenantNo(),s.currency(),s.storeNo(),s.brandNo());
    }
    private Map<String,Object> wallet(Query query,Map<String,Object> issuer){
        var s=query.scope();var person=query.customer();var accounts=db.rows("SELECT * FROM sv_account WHERE tenant_no=? AND issuer_no=? AND customer_no=? AND currency=? AND deleted=0",s.tenantNo(),text(issuer,"issuer_no"),person.customerNo(),s.currency());
        if(accounts.size()>1)throw BizException.of(ErrorCode.CONFLICT);if(accounts.isEmpty())return null;var account=accounts.getFirst();
        if(!Objects.equals(text(account,"entity_no"),text(issuer,"settlement_entity_no"))||!Objects.equals(text(account,"user_no"),person.userNo())||("STORE".equals(text(issuer,"scope_type"))?!s.storeNo().equals(text(account,"store_no")):text(account,"store_no")!=null))throw BizException.of(ErrorCode.CONFLICT);
        return account;
    }
    private String unavailable(ExecutionScope s,Map<String,Object> issuer,boolean issue){
        String type=text(issuer,"scope_type");
        if("BRAND".equals(type)&&!brandEnabled)return "BRAND_EXECUTION_DISABLED";
        if(!"ISSUER".equals(text(issuer,"write_mode")))return "ISSUER_NOT_READY";
        if(!(issue?"ACTIVE".equals(text(issuer,"status")):Set.of("ACTIVE","ISSUE_STOPPED").contains(text(issuer,"status"))))return "ISSUER_INACTIVE";
        if(!crossEntityEnabled&&!s.entityNo().equals(text(issuer,"settlement_entity_no")))return "CROSS_ENTITY_SETTLEMENT_UNAVAILABLE";
        var access=db.one("SELECT * FROM sv_issuer_store_access WHERE tenant_no=? AND issuer_no=? AND store_no=? AND deleted=0",s.tenantNo(),text(issuer,"issuer_no"),s.storeNo());
        if(access==null||!"ACTIVE".equals(text(access,"status"))||!s.entityNo().equals(text(access,"operating_entity_no"))||number(access,issue?"can_issue":"can_redeem")!=1)return "STORE_NOT_ACCEPTED";
        String binding="STORE".equals(type)?s.storeNo():s.brandBindingNo();long revision="STORE".equals(type)?s.storeRevision():s.brandBindingRevision();
        if(!Objects.equals(binding,text(access,"source_binding_no"))||revision!=number(access,"source_revision"))return "STORE_SCOPE_CHANGED";
        Instant from=instant(access.get("effective_from")),to=instant(access.get("effective_to"));
        if(from==null||Instant.now().isBefore(from)||to!=null&&!Instant.now().isBefore(to))return "STORE_NOT_ACCEPTED";
        return null;
    }
    private String filter(Query q){var s=q.scope();return EntitlementRecords.digest(db.encode(Arrays.asList(s.tenantNo(),s.managementNo(),s.entityNo(),s.storeNo(),s.storeRevision(),s.brandNo(),s.brandBindingNo(),s.brandBindingRevision(),s.currency(),q.customer().customerNo(),q.customer().userNo())));}
    private static Instant instant(Object value){if(value==null)return null;if(value instanceof java.sql.Timestamp time)return time.toInstant();if(value instanceof java.time.LocalDateTime time)return time.toInstant(java.time.ZoneOffset.UTC);if(value instanceof Instant time)return time;throw new IllegalStateException("Unsupported Pay timestamp");}
    private void requireEnabled(){if(!enabled)throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);}
    private static void notFound(){throw BizException.of(ErrorCode.NOT_FOUND);}
}

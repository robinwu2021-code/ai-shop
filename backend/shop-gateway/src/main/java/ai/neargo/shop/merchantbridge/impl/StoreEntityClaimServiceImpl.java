package ai.neargo.shop.merchantbridge.impl;

import ai.neargo.shop.merchantbridge.StoreRelinkLocalPolicy;

import ai.neargo.auth.store.SubjectKind;
import ai.neargo.commerce.api.common.StoreRef;
import ai.neargo.commerce.api.subscription.StoreRelinkFacade;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.paysync.NearPayStoreProfile;
import ai.neargo.shop.merchant.paysync.StoreSnapshotReader;
import ai.neargo.shop.merchant.paysync.StoreSyncPublisher;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import ai.neargo.shop.merchant.paysync.StoreSyncProperties;
import ai.neargo.shop.merchant.service.MerchantEntityService;
import ai.neargo.shop.merchant.service.StoreEntityClaimService;
import ai.neargo.shop.merchant.service.MerchantPlanService;
import ai.neargo.shop.payclient.PaySourceLinkApi;
import ai.neargo.shop.platform.OpsService;
import ai.neargo.svc.client.ServiceCallException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Each local stage commits before external I/O. Only the original business command is ever resumed. */
@Service
public class StoreEntityClaimServiceImpl implements StoreEntityClaimService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final StoreRelinkLocalPolicy policy;
    private final ObjectProvider<StoreRelinkFacade> subscriptions;
    private final ObjectProvider<PaySourceLinkApi> pay;
    private final StoreSnapshotReader snapshots;
    private final StoreSyncProperties sync;
    private final ObjectProvider<StoreSyncPublisher> publishers;
    private final MerchantEntityService entities;
    private final OpsService ops;
    private final MerchantPlanService plans;
    private final ObjectMapper json;
    private final boolean enabled;
    public StoreEntityClaimServiceImpl(JdbcTemplate jdbc,PlatformTransactionManager manager,StoreRelinkLocalPolicy policy,
            ObjectProvider<StoreRelinkFacade> subscriptions,ObjectProvider<PaySourceLinkApi> pay,StoreSnapshotReader snapshots,
            StoreSyncProperties sync,ObjectProvider<StoreSyncPublisher> publishers,MerchantEntityService entities,OpsService ops,MerchantPlanService plans,ObjectMapper json,
            @Value("${shop.merchant.store-claim.enabled:false}") boolean enabled) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.policy=policy;this.subscriptions=subscriptions;this.pay=pay;this.snapshots=snapshots;this.sync=sync;this.publishers=publishers;
        this.entities=entities;this.ops=ops;this.plans=plans;this.json=json;this.enabled=enabled;
    }
    @Override public View create(String tenant,String owner,String source,String store,Command c) {
        String hash=hash(tenant+"|"+owner+"|"+source+"|"+store+"|"+c.targetEntityNo()+"|"+c.expectedStoreVersion());
        var seen=findRequest(tenant,owner,c.requestNo());
        if(seen!=null) { sameHash(seen,hash); return advance(tenant,str(seen,"claim_no")); }
        if(!enabled || pay.getIfAvailable()==null || subscriptions.getIfAvailable()==null)
            throw BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
        String no;
        try { no=tx.execute(s -> freeze(tenant,owner,source,store,c,hash)); }
        catch(DuplicateKeyException race) {
            var original=findRequest(tenant,owner,c.requestNo());
            if(original==null) throw BizException.of(ErrorCode.CONFLICT);
            sameHash(original,hash);no=str(original,"claim_no");
        }
        return advance(tenant,no);
    }
    private String freeze(String tenant,String owner,String source,String store,Command c,String hash) {
        var old=findRequest(tenant,owner,c.requestNo());
        if(old!=null) {sameHash(old,hash);return str(old,"claim_no");}
        lockEntities(tenant,source,c.targetEntityNo());
        var st=jdbc.queryForMap("SELECT * FROM mch_store WHERE tenant_no=? AND store_no=? AND deleted=0 FOR UPDATE",tenant,store);
        if(!source.equals(st.get("entity_no"))) throw BizException.of(ErrorCode.CONFLICT);
        if(number(st,"version")!=c.expectedStoreVersion()) throw BizException.of(ErrorCode.CONFLICT);
        var checked=policy.inspect(tenant,owner,source,store,c.targetEntityNo(),null);
        if(!checked.blockers().isEmpty()) throw BizException.of(ErrorCode.CONFLICT);
        var snapshot=snapshots.read(store).orElseThrow(()->BizException.of(ErrorCode.NOT_FOUND));
        String no=BizKey.next("SEC");
        boolean retireSource=jdbc.queryForObject("SELECT COUNT(*) FROM mch_store WHERE tenant_no=? AND entity_no=? AND deleted=0",Integer.class,tenant,source)==1;
        jdbc.update("INSERT INTO mch_store_entity_claim(tenant_no,claim_no,owner_user_no,request_no,request_hash,source_scope,source_entity_no,target_entity_no,store_no,store_ref,expected_store_version,expected_pay_revision,source_retire_planned,state) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'FROZEN')",
                tenant,no,owner,c.requestNo(),hash,sync.sourceScope(),source,c.targetEntityNo(),store,Long.toString(snapshot.storeId()),c.expectedStoreVersion(),snapshot.revision()+NearPayStoreProfile.PROFILE_VERSION,retireSource);
        jdbc.update("UPDATE mch_entity SET ownership_change_no=?,version=version+1 WHERE tenant_no=? AND entity_no=? AND ownership_change_no IS NULL",no,tenant,source);
        jdbc.update("UPDATE mch_store SET ownership_change_no=?,version=version+1 WHERE tenant_no=? AND store_no=? AND ownership_change_no IS NULL",no,tenant,store);
        return no;
    }
    @Override public View byRequest(String tenant,String owner,String request) {
        var row=findRequest(tenant,owner,request);if(row==null) throw BizException.of(ErrorCode.NOT_FOUND);return view(row);
    }
    @Override public View byNo(String tenant,String owner,String no) {return view(owned(tenant,owner,no));}
    @Override public View resume(String tenant,String owner,String no) {owned(tenant,owner,no);return advance(tenant,no);}

    private View advance(String tenant,String no) {
        var api=pay.getIfAvailable();
        if(api==null) {error(tenant,no,"PAY_UNAVAILABLE");return view(row(tenant,no,false));}
        for(int phase=0;phase<5;phase++) {
            var claim=row(tenant,no,false);String state=str(claim,"state");
            if("COMPLETED".equals(state)||"ABORTED".equals(state)) return view(claim);
            var command=remote(claim);
            try {
                switch(state) {
                    case "FROZEN" -> {
                        PaySourceLinkApi.StoreClaimView remote;
                        try {remote=api.storeClaim(command.requestNo(),command.sourceScope());}
                        catch(ServiceCallException missing) {
                            if(missing.statusCode()!=404) throw missing;
                            remote=api.prepareStoreClaim(command);
                        }
                        verify(command,remote);
                        if("ABORTED".equals(remote.state())) {abortDecision(tenant,no,"PAY_ABORTED");continue;}
                        if(!"RESERVED".equals(remote.state())) {error(tenant,no,"PAY_STATE_CONFLICT");return view(row(tenant,no,false));}
                        final String ref=remote.claimRef();
                        tx.executeWithoutResult(s->jdbc.update("UPDATE mch_store_entity_claim SET state='PAY_PREPARED',remote_ref=?,last_error=NULL,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE tenant_no=? AND claim_no=? AND state='FROZEN'",ref,tenant,no));
                    }
                    case "PAY_PREPARED" -> {
                        try {tx.executeWithoutResult(s->commitLocal(tenant,no));}
                        catch(BizException rejected) {abortDecision(tenant,no,"LOCAL_REJECTED");}
                    }
                    case "LOCAL_COMMITTED" -> {
                        var remote=api.commitStoreClaim(command.requestNo(),command);verify(command,remote);
                        if(!"COMMITTED".equals(remote.state())) {error(tenant,no,"PAY_STATE_CONFLICT");return view(row(tenant,no,false));}
                        tx.executeWithoutResult(s->finish(tenant,no));
                    }
                    case "ABORTING" -> {
                        var remote=api.abortStoreClaim(command.requestNo(),command);verify(command,remote);
                        if(!"ABORTED".equals(remote.state())) {error(tenant,no,"PAY_STATE_CONFLICT");return view(row(tenant,no,false));}
                        tx.executeWithoutResult(s->abortLocal(tenant,no));
                    }
                    default -> throw new IllegalStateException("Unknown store claim state");
                }
            } catch(ServiceCallException call) {
                if((call.statusCode()==400||call.statusCode()==403||call.statusCode()==409) && "FROZEN".equals(state)) {
                    abortDecision(tenant,no,"PAY_REJECTED");continue;
                }
                error(tenant,no,"PAY_RESULT_UNKNOWN");return view(row(tenant,no,false));
            }
        }
        return view(row(tenant,no,false));
    }
    private void commitLocal(String tenant,String no) {
        var c=row(tenant,no,true);if(!"PAY_PREPARED".equals(c.get("state"))) return;
        String source=str(c,"source_entity_no"),target=str(c,"target_entity_no"),store=str(c,"store_no"),owner=str(c,"owner_user_no");
        lockEntities(tenant,source,target);
        var st=jdbc.queryForMap("SELECT * FROM mch_store WHERE tenant_no=? AND store_no=? AND deleted=0 FOR UPDATE",tenant,store);
        if(!source.equals(st.get("entity_no")) || !no.equals(st.get("ownership_change_no"))
                || number(st,"version")!=number(c,"expected_store_version")+1) throw BizException.of(ErrorCode.CONFLICT);
        var checked=policy.inspect(tenant,owner,source,store,target,no);
        if(!checked.blockers().isEmpty()) throw BizException.of(ErrorCode.CONFLICT);
        if("ACTIVE".equals(st.get("status"))) plans.requireStoreQuota(target,()->jdbc.queryForObject(
                "SELECT COUNT(*) FROM mch_store WHERE tenant_no=? AND entity_no=? AND status='ACTIVE' AND deleted=0",Integer.class,tenant,target));
        subscriptions.getObject().relinkFree(new StoreRelinkFacade.Command(new StoreRef(tenant,store),source,target,no));
        jdbc.update("UPDATE mch_store SET entity_no=?,is_default=0,pay_merchant_no=NULL,payment_changed_at=?,updated_at=GREATEST(CURRENT_TIMESTAMP(3),?),version=version+1 WHERE tenant_no=? AND store_no=? AND ownership_change_no=?",
                target,System.currentTimeMillis(),LocalDateTime.ofInstant(Instant.ofEpochMilli(number(c,"expected_pay_revision")-NearPayStoreProfile.PROFILE_VERSION+1),ZoneOffset.UTC),tenant,store,no);
        jdbc.update("DELETE FROM mch_store_role WHERE tenant_no=? AND store_no=? AND NOT EXISTS(SELECT 1 FROM mch_account a WHERE a.tenant_no=mch_store_role.tenant_no AND a.mch_account_no=mch_store_role.mch_account_no AND a.entity_no=? AND a.is_owner=1 AND a.deleted=0)",tenant,store,source);
        boolean retired=jdbc.queryForObject("SELECT COUNT(*) FROM mch_store WHERE tenant_no=? AND entity_no=? AND deleted=0",Integer.class,tenant,source)==0;
        if(retired != (number(c,"source_retire_planned")!=0)) throw BizException.of(ErrorCode.CONFLICT);
        if(retired) {
            jdbc.update("UPDATE mch_entity SET deleted=1,updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE tenant_no=? AND entity_no=? AND ownership_change_no=?",tenant,source,no);
            jdbc.update("UPDATE mch_account SET status='DISABLED',updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE tenant_no=? AND entity_no=?",tenant,source);
        }
        ops.audit("STORE_ENTITY_CLAIM",no,"占位主体门店认领",true,
                json.writeValueAsString(Map.of("entityNo",source,"storeNo",store)),json.writeValueAsString(Map.of("entityNo",target,"storeNo",store)));
        jdbc.update("UPDATE mch_store_entity_claim SET state='LOCAL_COMMITTED',revoked_staff=?,placeholder_retired=?,last_error=NULL,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE tenant_no=? AND claim_no=? AND state='PAY_PREPARED'",checked.revokedStaff(),retired,tenant,no);
    }
    private void finish(String tenant,String no) {
        var c=row(tenant,no,true);if(!"LOCAL_COMMITTED".equals(c.get("state"))) return;
        jdbc.update("UPDATE mch_store SET ownership_change_no=NULL,version=version+1 WHERE tenant_no=? AND store_no=? AND ownership_change_no=?",tenant,c.get("store_no"),no);
        jdbc.update("UPDATE mch_entity SET ownership_change_no=NULL,version=version+1 WHERE tenant_no=? AND entity_no=? AND ownership_change_no=?",tenant,c.get("source_entity_no"),no);
        var groups=entities.myStores(LoginUser.merchantByUser(str(c,"owner_user_no"),""));
        var result=new View(no,str(c,"request_no"),"COMPLETED",str(c,"store_no"),str(c,"source_entity_no"),str(c,"target_entity_no"),groups,((Number)c.get("revoked_staff")).intValue(),((Number)c.get("placeholder_retired")).intValue()!=0,null);
        publishers.ifAvailable(p->p.publishAll(List.of(str(c,"store_no"))));
        jdbc.update("UPDATE mch_store_entity_claim SET state='COMPLETED',result_json=?,last_error=NULL,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE tenant_no=? AND claim_no=? AND state='LOCAL_COMMITTED'",json.writeValueAsString(result),tenant,no);
    }
    private void abortDecision(String tenant,String no,String reason) {
        tx.executeWithoutResult(s->jdbc.update("UPDATE mch_store_entity_claim SET state='ABORTING',last_error=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE tenant_no=? AND claim_no=? AND state IN ('FROZEN','PAY_PREPARED')",reason,tenant,no));
    }
    private void abortLocal(String tenant,String no) {
        var c=row(tenant,no,true);if(!"ABORTING".equals(c.get("state"))) return;
        jdbc.update("UPDATE mch_store SET ownership_change_no=NULL,version=version+1 WHERE tenant_no=? AND store_no=? AND ownership_change_no=?",tenant,c.get("store_no"),no);
        jdbc.update("UPDATE mch_entity SET ownership_change_no=NULL,version=version+1 WHERE tenant_no=? AND entity_no=? AND ownership_change_no=?",tenant,c.get("source_entity_no"),no);
        jdbc.update("UPDATE mch_store_entity_claim SET state='ABORTED',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE tenant_no=? AND claim_no=? AND state='ABORTING'",tenant,no);
    }
    private void error(String tenant,String no,String message) {
        tx.executeWithoutResult(s->jdbc.update("UPDATE mch_store_entity_claim SET last_error=?,updated_at=CURRENT_TIMESTAMP WHERE tenant_no=? AND claim_no=? AND state NOT IN ('COMPLETED','ABORTED')",message,tenant,no));
    }
    private void lockEntities(String tenant,String a,String b) {
        jdbc.queryForList("SELECT entity_no FROM mch_entity WHERE tenant_no=? AND entity_no IN (?,?) ORDER BY entity_no FOR UPDATE",tenant,a,b);
    }
    private Map<String,Object> owned(String tenant,String owner,String no) {
        var c=row(tenant,no,false);if(!owner.equals(c.get("owner_user_no"))) throw BizException.of(ErrorCode.FORBIDDEN);return c;
    }
    private Map<String,Object> row(String tenant,String no,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM mch_store_entity_claim WHERE tenant_no=? AND claim_no=?"+(lock?" FOR UPDATE":""),tenant,no);
        if(rows.isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);return rows.getFirst();
    }
    private Map<String,Object> findRequest(String tenant,String owner,String request) {
        return jdbc.queryForList("SELECT * FROM mch_store_entity_claim WHERE tenant_no=? AND owner_user_no=? AND request_no=?",tenant,owner,request).stream().findFirst().orElse(null);
    }
    private View view(Map<String,Object> c) {
        if(c.get("result_json")!=null) return json.readValue(str(c,"result_json"),View.class);
        return new View(str(c,"claim_no"),str(c,"request_no"),str(c,"state"),str(c,"store_no"),str(c,"source_entity_no"),str(c,"target_entity_no"),null,((Number)c.get("revoked_staff")).intValue(),((Number)c.get("placeholder_retired")).intValue()!=0,str(c,"last_error"));
    }
    private PaySourceLinkApi.StoreClaimCommand remote(Map<String,Object> c) {
        return new PaySourceLinkApi.StoreClaimCommand(str(c,"claim_no"),str(c,"request_hash"),str(c,"source_scope"),str(c,"source_entity_no"),str(c,"target_entity_no"),str(c,"store_ref"),str(c,"store_no"),number(c,"expected_pay_revision"),number(c,"source_retire_planned")!=0);
    }
    private static void verify(PaySourceLinkApi.StoreClaimCommand c,PaySourceLinkApi.StoreClaimView v) {
        if(v==null||!c.requestNo().equals(v.requestNo())||!c.storeRef().equals(v.storeRef())||!c.storeNo().equals(v.storeNo())
                ||!c.sourceEntityRef().equals(v.sourceEntityRef())||!c.targetEntityRef().equals(v.targetEntityRef())) throw BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
    }
    private static void sameHash(Map<String,Object> c,String hash) {if(!hash.equals(c.get("request_hash"))) throw BizException.of(ErrorCode.CONFLICT);}
    private static String str(Map<String,Object> c,String key) {return c.get(key)==null?null:c.get(key).toString();}
    private static long number(Map<String,Object> c,String key) {return ((Number)c.get(key)).longValue();}
    private static String hash(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    @Override public void recoverPending() {
        for(var c:jdbc.queryForList("SELECT tenant_no,claim_no FROM mch_store_entity_claim WHERE state NOT IN ('COMPLETED','ABORTED') ORDER BY updated_at LIMIT 50"))
            advance(str(c,"tenant_no"),str(c,"claim_no"));
    }
}

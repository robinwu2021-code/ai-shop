package ai.neargo.shop.pay.points.store.impl;

import ai.neargo.shop.pay.points.lifecycle.PointsAccountControlService;
import ai.neargo.shop.pay.points.pool.PointsSwitchService.Scope;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.mapper.points.PointsConfigurationMapper;
import ai.neargo.shop.pay.points.store.LifecycleStore;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.List;
import ai.neargo.shop.pay.points.policy.ExpiryPolicy.Interval;
import ai.neargo.shop.pay.points.pool.PointsSwitchService.Command;
import ai.neargo.shop.pay.points.policy.SwitchPolicy;
import ai.neargo.shop.pay.points.policy.ExpiryPolicy;
import ai.neargo.shop.pay.points.model.AgreementSnapshot;
import ai.neargo.shop.pay.points.store.PointsReadStore.Availability;
import ai.neargo.shop.pay.points.pool.PointsSwitchService.Flow;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.sql.Timestamp;
import ai.neargo.shop.pay.points.policy.SwitchPolicy.Key;
import java.util.ArrayList;

/** Lifecycle metadata never bypasses the posting entry for balances or financial head transitions. */
@Repository
public class MybatisLifecycleStore implements LifecycleStore {
    private final PointsConfigurationMapper mapper;
    public MybatisLifecycleStore(PointsConfigurationMapper mapper) { this.mapper=mapper; }
    @Override public Map<String,Object> lockLifecycle(String type,String sub) {
        return DataScopeContext.executeWithoutScope(() -> mapper.lockLifecycle(type,sub));
    }
    @Override public Map<String,Object> lockUser(String type,String user) {
        return DataScopeContext.executeWithoutScope(() -> mapper.lockUser(type,user));
    }
    @Override public Map<String,Object> lockTransfer(String no) {
        return DataScopeContext.executeWithoutScope(() -> mapper.lockTransfer(no));
    }
    @Override public Map<String,Object> lockSwitch(String scope,String key) {
        return DataScopeContext.executeWithoutScope(() -> mapper.lockSwitch(scope,key));
    }
    @Override public void saveSwitch(Command c,String key,String payload,boolean previous) {
        DataScopeContext.executeWithoutScope(() -> {
            mapper.saveSwitch(c.typeCode(),c.enabled()?"ON":"OFF",payload,SwitchPolicy.scope(c),key);
            mapper.logSwitch(c.typeCode(),c.enabled()?"ON":"OFF",payload,SwitchPolicy.scope(c),key);
            if (previous && !c.enabled()) { mapper.openPause(c.typeCode(),SwitchPolicy.scope(c),key,c.at(),payload); }
            if (!previous && c.enabled() && mapper.closePause(SwitchPolicy.scope(c),key,c.at())!=1) {
                throw new IllegalStateException("POINTS_PAUSE_STATE_CONFLICT");
            }
            return null;
        });
    }
    @Override public List<Interval> pauses(String type,String user,long active,long asOf) {
        return DataScopeContext.executeWithoutScope(() -> {
            var typeRow=mapper.type(type);
            var keys=new ArrayList<>(SwitchPolicy.expiryKeys(type,user,(String)typeRow.get("market")));
            keys.add(new Key("FREEZE",SwitchPolicy.key(
                    Scope.USER,type,user)));
            var pauses=new ArrayList<>(mapper.scopedPauses(keys,active,asOf).stream().map(row ->
                    new Interval(((Number)row.get("from_at")).longValue(),
                            row.get("to_at")==null?asOf:((Number)row.get("to_at")).longValue())).toList());
            pauses.addAll(unavailable(type,user,(String)typeRow.get("market"),active,asOf));
            return List.copyOf(pauses);
        });
    }
    private List<Interval> unavailable(String type,String user,String market,long active,long asOf) {
        var agreements=mapper.acceptanceHistory(type);
        var history=mapper.acceptancePauses(type,active,asOf);
        if (agreements.size()>10000 || history.size()>10000) {
            throw new IllegalStateException("POINTS_AVAILABILITY_SCAN_LIMIT");
        }
        var available=new ArrayList<Interval>();
        var json=new ObjectMapper();
        for (var row:agreements) {
            var body=json.readTree((String)row.get("payload_json"));
            if (!body.hasNonNull("agreementNo")) { continue; }
            var agreement=json.treeToValue(body,AgreementSnapshot.class);
            long from=Math.max(active,time(row.get("created_at")));
            long to="ACTIVE".equals(row.get("state"))?asOf:Math.min(asOf,time(row.get("updated_at")));
            if (from>=to) { continue; }
            var selectors=SwitchPolicy.selectors(new Availability(type,user,agreement.storeNo(),
                    agreement.merchantNo(),market,null,null,Flow.REDEEM));
            var outages=history.stream().filter(p -> selectors.stream().anyMatch(k ->
                    k.scope().equals(p.get("scope")) && (k.key()==null || k.key().equals(p.get("scope_no")))))
                    .map(p -> new Interval(((Number)p.get("from_at")).longValue(),
                            p.get("to_at")==null?asOf:((Number)p.get("to_at")).longValue())).toList();
            available.addAll(ExpiryPolicy.usable(new Interval(from,to),outages));
        }
        return ExpiryPolicy.unavailable(active,asOf,available);
    }
    private long time(Object value) {
        if (value instanceof Timestamp stamp) { return stamp.toInstant().toEpochMilli(); }
        if (value instanceof LocalDateTime local) {
            return local.atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        }
        throw new IllegalStateException("POINTS_ACCEPTANCE_TIME_UNKNOWN");
    }
    @Override public void freeze(PointsAccountControlService.Command c,
                                 String payload,long version,boolean previous) {
        DataScopeContext.executeWithoutScope(() -> {
            if (mapper.freeze(c.typeCode(),c.userNo(),version,c.frozen()?1:0)!=1) {
                throw new IllegalStateException("POINTS_ACCOUNT_VERSION_CONFLICT");
            }
            String key=SwitchPolicy.key(Scope.USER,
                    c.typeCode(),c.userNo());
            mapper.saveSwitch(c.typeCode(),c.frozen()?"OFF":"ON",payload,"FREEZE",key);
            mapper.logSwitch(c.typeCode(),c.frozen()?"OFF":"ON",payload,"FREEZE",key);
            if (!previous && c.frozen()) { mapper.openPause(c.typeCode(),"FREEZE",key,c.at(),payload); }
            if (previous && !c.frozen() && mapper.closePause("FREEZE",key,c.at())!=1) {
                throw new IllegalStateException("POINTS_PAUSE_STATE_CONFLICT");
            }
            return null;
        });
    }
    @Override public void markReminded(String type,String user,long revision) {
        int changed=DataScopeContext.executeWithoutScope(() -> mapper.markReminded(type,user,revision));
        if (changed!=1) { throw new IllegalStateException("POINTS_EXPIRY_REVISION_CONFLICT"); }
    }
    @Override public boolean clearForRetirement(String type) {
        return DataScopeContext.executeWithoutScope(() -> {
            var pool=mapper.lockPoolSnapshot(type);
            if (pool==null) { return false; }
            for (String field:List.of("u_minor","c_minor","d_minor","b_minor","reserved_b_minor",
                    "locked_u_minor","locked_b_minor")) {
                if (((Number)pool.get(field)).longValue()!=0) { return false; }
            }
            return mapper.liveAccount(type)==null && mapper.liveRedemption(type)==null && mapper.liveDebt(type)==null
                    && mapper.livePayout(type)==null && mapper.liveRefund(type)==null && mapper.liveStatement(type)==null
                    && mapper.liveTransfer(type)==null && mapper.liveApproval(type)==null && mapper.livePlan(type)==null;
        });
    }
}

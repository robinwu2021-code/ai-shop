package ai.neargo.shop.pay.points.store.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.mapper.points.PointsAvailabilityMapper;
import ai.neargo.shop.pay.points.model.AgreementSnapshot;
import ai.neargo.shop.pay.points.policy.ExpiryPolicy;
import ai.neargo.shop.pay.points.policy.ExpiryPolicy.Interval;
import ai.neargo.shop.pay.points.policy.SwitchPolicy;
import ai.neargo.shop.pay.points.pool.PointsSwitchService.Flow;
import ai.neargo.shop.pay.points.store.PointsReadStore.Availability;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/** Metadata only invalidates a watermark; recovery rebuilds coverage off the user request path. */
@Repository
@Transactional(value="payTxManager", isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
public class AcceptanceAvailabilityProjection {
    private final PointsAvailabilityMapper mapper;
    private final ObjectMapper json=new ObjectMapper();
    public AcceptanceAvailabilityProjection(PointsAvailabilityMapper mapper) { this.mapper=mapper; }
    @org.springframework.beans.factory.annotation.Value("${shop.points.default-participation-enabled:${shop.points.unified-earning-enabled:false}}")
    private boolean defaultParticipation;
    @org.springframework.beans.factory.annotation.Value("${shop.points.default-participation-since:0}")
    private long participationSince;
    @org.springframework.context.event.EventListener(org.springframework.context.event.ContextRefreshedEvent.class)
    public void initializeDefaultParticipation() {
        if (!defaultParticipation) return;
        if (participationSince<=0) throw new IllegalArgumentException("POINTS_PARTICIPATION_START_REQUIRED");
        for (var type : mapper.types()) rebuild(type);
    }

    public List<Interval> current(String type) {
        return DataScopeContext.executeWithoutScope(() -> {
            requireType(type);
            var snapshot=mapper.snapshot(type);
            if(snapshot==null) throw new IllegalStateException("POINTS_AVAILABILITY_PROJECTION_PENDING");
            if(number(snapshot,"source_version")!=number(snapshot,"projected_version"))
                throw new IllegalStateException("POINTS_AVAILABILITY_PROJECTION_PENDING");
            return json.readValue((String)snapshot.get("payload_json"),new TypeReference<List<Interval>>(){});
        });
    }
    public void changed(String type) {
        DataScopeContext.executeWithoutScope(() -> {
            if("*".equals(type)) for(var t:mapper.types()) mapper.dirty(t);
            else mapper.dirty(type);
            return null;
        });
    }
    public void repair(String type) {
        DataScopeContext.executeWithoutScope(() -> {
            requireType(type);
            var snapshot=mapper.snapshot(type);
            if(snapshot==null || number(snapshot,"source_version")!=number(snapshot,"projected_version"))
                rebuildLocked(type,snapshot);
            return null;
        });
    }
    public void rebuild(String type) {
        DataScopeContext.executeWithoutScope(() -> {
            requireType(type); rebuildLocked(type,mapper.snapshot(type)); return null;
        });
    }
    private void requireType(String type) {
        if(mapper.lockType(type)==null) throw new IllegalArgumentException("POINTS_TYPE_UNKNOWN");
    }
    private List<Interval> rebuildLocked(String type,Map<String,Object> prior) {
        String market=(String)mapper.lockType(type).get("market");
        var bySelector = new HashMap<SwitchPolicy.Key, List<Interval>>();
        for (var row : pages(type, false)) {
            String scope = (String) row.get("scope"), subject = (String) row.get("scope_no");
            var interval = new Interval(number(row,"from_at"), row.get("to_at")==null
                    ? Long.MAX_VALUE : number(row,"to_at"));
            bySelector.computeIfAbsent(new SwitchPolicy.Key(scope,subject), k -> new ArrayList<>()).add(interval);
            if (subject != null) bySelector.computeIfAbsent(new SwitchPolicy.Key(scope,null), k -> new ArrayList<>()).add(interval);
        }
        var routes = new LinkedHashMap<Route,List<Interval>>();
        for (var row : pages(type, true)) {
            var data=json.readTree((String)row.get("payload_json"));
            if(!data.hasNonNull("agreementNo"))continue;
            var agreement=json.treeToValue(data,AgreementSnapshot.class);
            long from=time(row.get("created_at"));
            long to="ACTIVE".equals(row.get("state"))?Long.MAX_VALUE:time(row.get("updated_at"));
            if(from<to) routes.computeIfAbsent(new Route(agreement.storeNo(),agreement.merchantNo()),
                    k -> new ArrayList<>()).add(new Interval(from,to));
        }
        var available=new ArrayList<Interval>();
        for (var route : routes.entrySet()) {
            var keys=SwitchPolicy.selectors(new Availability(type,null,route.getKey().store(),
                    route.getKey().merchant(),market,null,null,Flow.REDEEM)).stream()
                    .filter(k -> k.scope().endsWith("STORE") || k.scope().endsWith("MERCHANT")).toList();
            var outages=keys.stream().flatMap(k -> bySelector.getOrDefault(k,List.of()).stream()).toList();
            available.addAll(ExpiryPolicy.coverage(route.getValue(),outages));
        }
        if(defaultParticipation) {
            if(participationSince<=0)throw new IllegalArgumentException("POINTS_PARTICIPATION_START_REQUIRED");
            available.add(new Interval(participationSince,Long.MAX_VALUE));
        }
        var common=SwitchPolicy.selectors(new Availability(type,null,null,null,market,null,null,Flow.REDEEM)).stream()
                .filter(k -> !k.scope().endsWith("USER") && !k.scope().endsWith("STORE") && !k.scope().endsWith("MERCHANT"))
                .flatMap(k -> bySelector.getOrDefault(k,List.of()).stream()).toList();
        var merged=ExpiryPolicy.coverage(available,common);
        String body=json.writeValueAsString(merged);
        long version=prior==null?1:number(prior,"source_version");
        if(prior==null) mapper.insert(type,version,body); else mapper.save(type,version,body);
        return merged;
    }
    private List<Map<String,Object>> pages(String type,boolean agreements) {
        var result=new ArrayList<Map<String,Object>>(); long cursor=0;
        while(true) {
            var page=agreements?mapper.agreements(type,cursor,500):mapper.pauses(type,cursor,500);
            result.addAll(page);
            if(page.size()<500) return result;
            cursor=number(page.getLast(),"id");
        }
    }
    private record Route(String store, String merchant) {}
    private long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    private long time(Object value) {
        if(value instanceof Timestamp t)return t.toInstant().toEpochMilli();
        if(value instanceof LocalDateTime t)return t.atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        throw new IllegalStateException("POINTS_ACCEPTANCE_TIME_UNKNOWN");
    }
}

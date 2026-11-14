package ai.neargo.shop.pay.service.impl;

import ai.neargo.shop.pay.service.PlatformCashReporting;
import ai.neargo.shop.pay.service.PlatformCollectionService.Request;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import javax.sql.DataSource;
import java.util.*;
import java.util.function.Consumer;

/** A single local ledger snapshot. Source ownership and permission checks belong to the host callback. */
@Service
@ConditionalOnProperty(prefix="commerce",name={"enabled","mall.payment.enabled"},havingValue="true")
public class PlatformCashReportingImpl implements PlatformCashReporting {
    private static final int BATCH=500;
    private static final String SCENES="pay_scene IN ('PLATFORM_MALL','MALL_UNMATCHED')";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate read;
    private final JsonMapper json=JsonMapper.builder().build();
    public PlatformCashReportingImpl(@Qualifier("payDataSource") DataSource ds,
                                     @Qualifier("payTxManager") PlatformTransactionManager manager) {
        jdbc=new JdbcTemplate(ds);read=new TransactionTemplate(manager);
        read.setReadOnly(true);read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Override public void scan(Query query,Consumer<List<Fact>> consumer) {
        Objects.requireNonNull(query,"query");Objects.requireNonNull(consumer,"consumer");
        read.executeWithoutResult(tx->{
            long afterTime=query.fromInclusive();String afterNo="";
            while(true) {
                var args=new ArrayList<Object>(List.of(query.tenantNo(),query.fromInclusive(),query.toExclusive(),afterTime,afterTime,afterNo));
                if(query.currency()!=null)args.add(query.currency());
                var rows=jdbc.queryForList("SELECT * FROM stl_payment WHERE tenant_no=? AND deleted=0 AND status='SUCCESS' AND direction IN ('PAY','REFUND') AND "+SCENES
                        +" AND succeeded_at>=? AND succeeded_at<? AND (succeeded_at>? OR (succeeded_at=? AND payment_no>?))"
                        +(query.currency()==null?"":" AND currency=?")+" ORDER BY succeeded_at,payment_no LIMIT "+BATCH,args.toArray());
                if(rows.isEmpty())break;
                consumer.accept(facts(query.tenantNo(),rows));
                var last=rows.getLast();afterTime=number(last,"succeeded_at");afterNo=string(last,"payment_no");
                if(rows.size()<BATCH)break;
            }
        });
    }
    private record Original(Map<String,Object> row,Request request,String classification,boolean verified) {}
    private List<Fact> facts(String tenant,List<Map<String,Object>> rows) {
        var ids=new LinkedHashSet<String>();
        for(var row:rows) {
            String no=string(row,"PAY".equals(row.get("direction"))?"payment_no":"origin_payment_no");
            if(no!=null)ids.add(no);
        }
        var originals=new HashMap<String,Original>();
        if(!ids.isEmpty()) {
            var args=new ArrayList<Object>();args.add(tenant);args.addAll(ids);
            for(var row:jdbc.queryForList("SELECT * FROM stl_payment WHERE tenant_no=? AND direction='PAY' AND deleted=0 AND "+SCENES
                    +" AND payment_no IN ("+marks(ids.size())+") LIMIT "+BATCH,args.toArray())) {
                Request request=null;boolean verified=false;
                try { request=PlatformCashAuthority.receiptMetadata(json,row);verified=PlatformCashAuthority.receiptVerified(json,request,row); }
                catch(RuntimeException invalid) { /* A sanitized invalid candidate still reaches host scope filtering. */ }
                originals.put(string(row,"payment_no"),new Original(row,request,classification(row),verified));
            }
        }
        var invalidCapacity=invalidCapacity(tenant,originals);
        var result=new ArrayList<Fact>(rows.size());
        for(var row:rows) {
            boolean refund="REFUND".equals(row.get("direction"));
            String originalNo=refund?string(row,"origin_payment_no"):string(row,"payment_no");
            var original=originals.get(originalNo);
            boolean verified=original!=null && original.verified();
            String diagnostic=verified?null:"PLATFORM_CASH_ORIGINAL_UNVERIFIED";
            if(verified && refund) {
                try { verified=PlatformCashAuthority.refundVerified(json,original.request(),originalNo,row); }
                catch(RuntimeException invalid) { verified=false; }
                if(!verified)diagnostic="PLATFORM_CASH_REFUND_UNVERIFIED";
            }
            if(invalidCapacity.contains(originalNo)) { verified=false;diagnostic="PLATFORM_CASH_REFUND_CAPACITY_INVALID"; }
            result.add(new Fact(string(row,"payment_no"),string(row,"order_no"),refund?originalNo:null,
                    string(row,"direction"),string(row,"currency"),number(row,"amount_minor"),number(row,"succeeded_at"),
                    original==null?classification(row):original.classification(),verified,diagnostic));
        }
        return List.copyOf(result);
    }
    /** Check the full original's refund history, including facts outside this report's occurrence window. */
    private Set<String> invalidCapacity(String tenant,Map<String,Original> originals) {
        if(originals.isEmpty())return Set.of();
        var totals=new HashMap<String,Long>();var invalid=new HashSet<String>();String after="";
        while(true) {
            var args=new ArrayList<Object>();args.add(tenant);args.addAll(originals.keySet());args.add(after);
            var rows=jdbc.queryForList("SELECT * FROM stl_payment WHERE tenant_no=? AND direction='REFUND' AND status='SUCCESS' AND deleted=0"
                    +" AND origin_payment_no IN ("+marks(originals.size())+") AND payment_no>? ORDER BY payment_no LIMIT "+BATCH,args.toArray());
            for(var row:rows) {
                String no=string(row,"origin_payment_no");var original=originals.get(no);
                if(!original.verified() || "RELEASED".equals(row.get("refund_capacity_state")))continue;
                try {
                    if(PlatformCashAuthority.refundVerified(json,original.request(),no,row)) {
                        long total=Math.addExact(totals.getOrDefault(no,0L),number(row,"amount_minor"));
                        totals.put(no,total);if(total>original.request().amountMinor())invalid.add(no);
                    }
                } catch(ArithmeticException overflow) { invalid.add(no); }
                catch(RuntimeException unverified) { /* Invalid successful facts are flagged individually, never money. */ }
            }
            if(rows.size()<BATCH)break;after=string(rows.getLast(),"payment_no");
        }
        return invalid;
    }
    private static String classification(Map<String,Object> row) {
        return "MALL_UNMATCHED".equals(row.get("pay_scene"))?"UNMATCHED":row.get("platform_close_fence")!=null?"CLOSED_ORDER":"NORMAL";
    }
    private static String marks(int count) { return String.join(",",Collections.nCopies(count,"?")); }
    private static String string(Map<String,Object> row,String key) { return (String)row.get(key); }
    private static long number(Map<String,Object> row,String key) { return row.get(key) instanceof Number n?n.longValue():0; }
}

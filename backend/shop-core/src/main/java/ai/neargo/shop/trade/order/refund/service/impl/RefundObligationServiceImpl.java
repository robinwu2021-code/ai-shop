package ai.neargo.shop.trade.order.refund.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.marketing.CouponPort;
import ai.neargo.shop.trade.order.mapper.RefundObligationMapper;
import ai.neargo.shop.trade.order.refund.service.RefundExecutionStore;
import ai.neargo.shop.trade.order.refund.service.RefundObligationService;
import ai.neargo.shop.trade.order.refund.service.impl.RefundCouponScopeServiceImpl.Scope;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class RefundObligationServiceImpl implements RefundObligationService {
    private final RefundObligationMapper mapper;
    private final RefundExecutionStore executions;
    private final CouponPort coupons;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    public RefundObligationServiceImpl(RefundObligationMapper mapper,RefundExecutionStore executions,
            CouponPort coupons,ObjectMapper json,@Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.mapper=mapper; this.executions=executions; this.coupons=coupons; this.json=json;
        this.tx=new TransactionTemplate(manager);
    }
    @Override public boolean hasScope(String order) {
        return DataScopeContext.executeWithoutScope(() -> mapper.scope(order)!=null);
    }
    @Override public boolean versioned(String no) {
        return DataScopeContext.executeWithoutScope(() -> {
            var source=source(no);
            return source!=null && source.version()==2;
        });
    }
    private Source source(String no) {
        if(no.startsWith("PRF-")) {
            var row=mapper.pointsSource(no.substring(4));
            if(row==null) return null;
            boolean ready="COMMITTED".equals(row.get("state"))
                    && ("POINTS_CONFIRMED".equals(row.get("phase")) || "COMMITTED".equals(row.get("phase")));
            return new Source((String)row.get("order_no"),((Number)row.get("closure_version")).intValue(),
                    Boolean.TRUE.equals(row.get("return_coupon")) || row.get("return_coupon") instanceof Number n && n.intValue()==1,ready);
        }
        var row=executions.read(no);
        return row==null?null:new Source(row.getOrderNo(),row.getClosureVersion()==null?1:row.getClosureVersion(),
                Boolean.TRUE.equals(row.getReturnCoupon()),"SUCCEEDED".equals(row.getStatus()));
    }
    private record Source(String orderNo,int version,boolean returnCoupon,boolean ready) { }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void prepare(String no) {
        DataScopeContext.executeWithoutScope(() -> {
            var execution=source(no);
            require(execution!=null && execution.version()==2 && execution.ready(),"REFUND_OBLIGATION_SOURCE_NOT_READY");
            // Serializes competing receipts across the original coupon's payment scope.
            require(mapper.lockPayment(execution.orderNo())!=null,"REFUND_OBLIGATION_ORDER_MISSING");
            if(mapper.lockRows(no).stream().anyMatch(row -> "COUPON".equals(row.get("kind")))) return null;
            var row=mapper.scope(execution.orderNo());
            require(row!=null,"REFUND_COUPON_SCOPE_REQUIRED");
            String payload=(String)row.get("payload_json");
            require(RefundCouponScopeServiceImpl.hash(payload).equals(row.get("input_hash")),"REFUND_COUPON_SCOPE_CORRUPT");
            var scope=json.readValue(payload,Scope.class);
            require(execution.orderNo().equals(scope.orderNo()),"REFUND_COUPON_SCOPE_CONFLICT");
            boolean eligible=execution.returnCoupon() && scope.couponNo()!=null
                    && !scope.subOrderNos().isEmpty() && scope.subOrderNos().stream().allMatch(sub ->
                        "CANCELLED".equals(mapper.childStatus(sub))
                        || mapper.itemCount(sub)>0 && mapper.unrefundedItems(sub)==0);
            String state=eligible?"PENDING":"NOT_REQUIRED";
            mapper.create(no,scope.couponNo()==null?scope.orderNo():scope.couponNo(),(String)row.get("input_hash"),
                    payload,state,eligible?null:"POLICY:"+no);
            return null;
        });
    }
    @Override public boolean settled(String no) {
        return DataScopeContext.executeWithoutScope(() -> {
            var rows=mapper.rows(no).stream().filter(row -> "COUPON".equals(row.get("kind"))).toList();
            return !rows.isEmpty() && rows.stream().allMatch(row -> terminal((String)row.get("state")));
        });
    }
    @Override public Progress progress(String no) {
        return DataScopeContext.executeWithoutScope(() -> {
            if(no.startsWith("PRF-")) {
                var row=mapper.pointsSource(no.substring(4));
                if(row==null || ((Number)row.get("closure_version")).intValue()!=2) return null;
                Long cash=row.get("net_cash_minor") instanceof Number n?n.longValue():null;
                if(cash==null && row.get("preparation_json") instanceof String prep) {
                    cash=json.readValue(prep,ai.neargo.shop.spi.points.PointsRefundPort.RefundPreparation.class).netCashMinor();
                }
                boolean confirmed="CASH_CONFIRMED".equals(row.get("state")) || "COMMITTED".equals(row.get("state"));
                boolean manual=List.of("COMPENSATED_PENDING_OPERATOR","CASH_FAILED_PENDING_OPERATOR")
                        .contains(row.get("phase"));
                boolean unknown=List.of("CASH_OPENING","CASH_PENDING").contains(row.get("state"));
                String cashStatus=cash!=null && cash==0?"NOT_REQUIRED":confirmed?"CONFIRMED":unknown?"UNKNOWN":"PENDING";
                var stock=mapper.rows(no).stream().filter(task -> "STOCK".equals(task.get("kind"))).findFirst().orElse(null);
                String resource=stock==null?"MANAGED_SEPARATELY":terminal((String)stock.get("state"))?"DONE":"PENDING";
                return new Progress(2,"REFUNDED".equals(row.get("after_sale_status")) && confirmed?"DONE":"PENDING",
                        cashStatus,settled(no)?"SETTLED":"PENDING",(String)row.get("cash_payment_no"),
                        manual?"MANUAL_REQUIRED":confirmed?"SUCCEEDED":unknown?"UNKNOWN":"PROCESSING",
                        resource,stock==null?null:(String)stock.get("error_code"));
            }
            var row=executions.read(no);
            if(row==null) return null;
            int version=row.getClosureVersion()==null?1:row.getClosureVersion();
            String cash=row.getAmountMinor()==0?"NOT_REQUIRED":"SUCCEEDED".equals(row.getStatus())?"CONFIRMED":row.getStatus();
            return new Progress(version,row.getClosureStatus(),cash,version==2?(settled(no)?"SETTLED":"PENDING"):null,
                    row.getPaymentNo(),row.getStatus(),row.getResourceStatus(),row.getResourceErrorCode());
        });
    }
    @Override @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void recover(String no) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),"REFUND_OBLIGATION_REQUIRES_NO_TRANSACTION");
        var work=tx.execute(s -> DataScopeContext.executeWithoutScope(() -> {
            var rows=mapper.lockRows(no);
            if(rows.isEmpty()) return List.<Map<String,Object>>of();
            long now=System.currentTimeMillis();
            return rows.stream().filter(row -> "COUPON".equals(row.get("kind"))).filter(row -> !terminal((String)row.get("state")))
                .filter(row -> !"PROCESSING".equals(row.get("state")) || number(row,"next_attempt_at")<=now)
                .filter(row -> mapper.update(number(row,"id"),(String)row.get("input_hash"),"PROCESSING",null,null,now+120000)==1)
                .toList();
        }));
        if(work==null) return;
        for(var row:work) {
            String proof=null;
            String error=null;
            try {
                String payload=(String)row.get("payload_json");
                require(RefundCouponScopeServiceImpl.hash(payload).equals(row.get("input_hash")),"REFUND_OBLIGATION_INPUT_CORRUPT");
                var scope=json.readValue(payload,Scope.class);
                coupons.release(scope.orderNo()); // Same original order, including retries after a lost acknowledgement.
                proof="COUPON_RELEASE:"+scope.orderNo();
            } catch(RuntimeException failure) {error="COUPON_RELEASE_UNCONFIRMED";}
            String resultProof=proof, resultError=error;
            tx.executeWithoutResult(s -> DataScopeContext.executeWithoutScope(() -> mapper.update(number(row,"id"),
                    (String)row.get("input_hash"),resultProof!=null?"SUCCEEDED":"UNKNOWN",resultProof,resultError,
                    System.currentTimeMillis()+120000)));
        }
    }
    private static boolean terminal(String state) {return "SUCCEEDED".equals(state)||"NOT_REQUIRED".equals(state);}
    private static long number(Map<String,Object> row,String key) {return ((Number)row.get(key)).longValue();}
    private static void require(boolean valid,String error) {if(!valid) throw new IllegalStateException(error);}
}

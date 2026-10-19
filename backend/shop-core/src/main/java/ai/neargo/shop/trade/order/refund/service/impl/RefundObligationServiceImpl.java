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
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void prepare(String no) {
        DataScopeContext.executeWithoutScope(() -> {
            var execution=executions.read(no);
            require(execution!=null && Integer.valueOf(2).equals(execution.getClosureVersion())
                    && "SUCCEEDED".equals(execution.getStatus()),"REFUND_OBLIGATION_SOURCE_NOT_READY");
            // Serializes competing receipts across the original coupon's payment scope.
            require(mapper.lockPayment(execution.getOrderNo())!=null,"REFUND_OBLIGATION_ORDER_MISSING");
            if(!mapper.lockRows(no).isEmpty()) return null;
            var row=mapper.scope(execution.getOrderNo());
            require(row!=null,"REFUND_COUPON_SCOPE_REQUIRED");
            String payload=(String)row.get("payload_json");
            require(RefundCouponScopeServiceImpl.hash(payload).equals(row.get("input_hash")),"REFUND_COUPON_SCOPE_CORRUPT");
            var scope=json.readValue(payload,Scope.class);
            require(execution.getOrderNo().equals(scope.orderNo()),"REFUND_COUPON_SCOPE_CONFLICT");
            boolean eligible=Boolean.TRUE.equals(execution.getReturnCoupon()) && scope.couponNo()!=null
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
            var rows=mapper.rows(no);
            return !rows.isEmpty() && rows.stream().allMatch(row -> terminal((String)row.get("state")));
        });
    }
    @Override @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void recover(String no) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),"REFUND_OBLIGATION_REQUIRES_NO_TRANSACTION");
        var work=tx.execute(s -> DataScopeContext.executeWithoutScope(() -> {
            var rows=mapper.lockRows(no);
            if(rows.isEmpty()) return List.<Map<String,Object>>of();
            long now=System.currentTimeMillis();
            return rows.stream().filter(row -> !terminal((String)row.get("state")))
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

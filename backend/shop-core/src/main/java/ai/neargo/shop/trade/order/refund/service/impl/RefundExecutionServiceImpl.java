package ai.neargo.shop.trade.order.refund.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.settle.SettlePort.RefundEvidence;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.order.mapper.RefundExecutionMapper;
import ai.neargo.shop.trade.order.mapper.RefundLedgerMapper;
import ai.neargo.shop.trade.order.refund.model.LedgerResult;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import ai.neargo.shop.trade.order.refund.policy.LineShares;
import ai.neargo.shop.trade.order.refund.port.impl.MybatisRefundLedgerStore;
import ai.neargo.shop.trade.order.refund.service.RefundExecutionService;
import ai.neargo.shop.trade.order.refund.service.RefundLedger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Holds source capacity without calling a bank; all source changes commit in the platform transaction. */
@Service
@Transactional("transactionManager")
public class RefundExecutionServiceImpl implements RefundExecutionService {
    private final RefundExecutionMapper store;
    private final RefundLedgerMapper ledgerStore;
    private final SettlePort settle;
    private final ObjectMapper json=new ObjectMapper();
    public RefundExecutionServiceImpl(RefundExecutionMapper store,RefundLedgerMapper mapper,SettlePort settle) {
        this.store=store; this.ledgerStore=mapper; this.settle=settle;
    }
    @Override public Preparation prepare(String as,RefundPlan plan) {
        return DataScopeContext.executeWithoutScope(() -> {
            require(as!=null && !as.isBlank() && plan!=null,"REFUND_EXECUTION_INPUT_INVALID");
            var sub=store.lockSub(plan.subOrderNo());
            require(sub!=null,"REFUND_EXECUTION_SOURCE_UNKNOWN");
            var prior=store.lockExecution(as);
            if (prior!=null) {
                require(snapshot(prior).plan().equals(plan),"REFUND_EXECUTION_INPUT_CONFLICT");
                require(!"ABORTED".equals(prior.get("state")),"REFUND_EXECUTION_ABORTED");
                return preparation(prior);
            }
            var afterSale=store.lockAfterSale(as);
            require(afterSale!=null && plan.subOrderNo().equals(afterSale.getSubOrderNo())
                    && sub.getUserNo().equals(afterSale.getUserNo()),"REFUND_EXECUTION_SUBJECT_MISMATCH");
            require("REFUNDING".equals(afterSale.getStatus()) && "CASH".equals(afterSale.getRefundMode()),
                    "REFUND_EXECUTION_SOURCE_NOT_READY");
            long approved=afterSale.getApprovedMinor()==null?afterSale.getRefundMinor():afterSale.getApprovedMinor();
            require(plan.totalMinor()==approved,"REFUND_EXECUTION_APPROVAL_MISMATCH");
            capacity(sub,store.items(plan.subOrderNo()),plan);
            String currency=settle.paymentCurrency(sub.getOrderNo());
            require(currency!=null && !currency.isBlank(),"REFUND_EXECUTION_CURRENCY_UNKNOWN");
            require(sub.getStoreNo()!=null && !sub.getStoreNo().isBlank(),"REFUND_EXECUTION_STORE_REQUIRED");
            var frozen=new Snapshot(as,sub.getOrderNo(),sub.getUserNo(),sub.getStoreNo(),currency,plan);
            String payload=json.writeValueAsString(frozen);
            store.create(as,plan.subOrderNo(),hash(payload),payload);
            return preparation(store.execution(as));
        });
    }
    @Override public CashIntent beginCash(String as) {
        return DataScopeContext.executeWithoutScope(() -> {
            var head=locked(as);
            require(List.of("PREPARED","CASH_OPENING","CASH_PENDING","CASH_CONFIRMED","COMMITTED")
                    .contains(head.get("state")),"REFUND_EXECUTION_PHASE_CONFLICT");
            var saved=snapshot(head);
            if ("PREPARED".equals(head.get("state"))) {
                var sub=store.lockSub(saved.plan().subOrderNo());
                require(nz(sub.getPointsDeduct())==0 && nz(sub.getPointsDeductMinor())==0
                        && !Boolean.TRUE.equals(sub.getPointsGranted()),
                        "REFUND_EXECUTION_POINTS_CONFIRMATION_REQUIRED");
                change(head,"CASH_OPENING",null,saved.plan().totalMinor(),null);
            }
            return new CashIntent(as,saved.plan().subOrderNo(),saved.plan().totalMinor());
        });
    }
    @Override public void bindCash(String as,String payment,long net) {
        DataScopeContext.executeWithoutScope(() -> {
            var head=locked(as);
            require(payment!=null && !payment.isBlank(),"REFUND_EXECUTION_CASH_REQUIRED");
            // Offset/zero-cash plans need their points revision and completion evidence in the subsequent wiring task.
            require(net==snapshot(head).plan().totalMinor() && net>0,"REFUND_EXECUTION_CASH_PLAN_UNSUPPORTED");
            if (head.get("cash_payment_no")!=null) {
                require(payment.equals(head.get("cash_payment_no")) && net==number(head,"net_cash_minor"),
                        "REFUND_EXECUTION_CASH_CONFLICT");
                return null;
            }
            require("CASH_OPENING".equals(head.get("state")),"REFUND_EXECUTION_PHASE_CONFLICT");
            matchingIntent(head,settle.refundEvidence(payment),net);
            change(head,"CASH_PENDING",payment,net,null);
            return null;
        });
    }
    @Override public void confirmCash(String as) {
        DataScopeContext.executeWithoutScope(() -> {
            var head=locked(as);
            require(List.of("CASH_PENDING","CASH_CONFIRMED","COMMITTED").contains(head.get("state")),
                    "REFUND_EXECUTION_PHASE_CONFLICT");
            verified(head);
            if ("CASH_PENDING".equals(head.get("state"))) {
                change(head,"CASH_CONFIRMED",(String)head.get("cash_payment_no"),number(head,"net_cash_minor"),null);
            }
            return null;
        });
    }
    @Override public LedgerResult commit(String as) {
        return DataScopeContext.executeWithoutScope(() -> {
            var head=locked(as);
            if ("COMMITTED".equals(head.get("state"))) {
                return json.readValue((String)head.get("result_json"),LedgerResult.class);
            }
            require("CASH_CONFIRMED".equals(head.get("state")),"REFUND_EXECUTION_NOT_CONFIRMED");
            verified(head);
            var sub=store.lockSub((String)head.get("sub_order_no"));
            require(nz(sub.getPointsDeduct())==0 && nz(sub.getPointsDeductMinor())==0
                    && !Boolean.TRUE.equals(sub.getPointsGranted()),
                    "REFUND_EXECUTION_POINTS_CONFIRMATION_REQUIRED");
            var result=new RefundLedger(new MybatisRefundLedgerStore(ledgerStore,as)).record(snapshot(head).plan());
            change(head,"COMMITTED",(String)head.get("cash_payment_no"),number(head,"net_cash_minor"),
                    json.writeValueAsString(result));
            return result;
        });
    }
    @Override public void release(String as) {
        DataScopeContext.executeWithoutScope(() -> {
            var head=locked(as);
            if ("ABORTED".equals(head.get("state"))) { return null; }
            require("PREPARED".equals(head.get("state")) && head.get("cash_payment_no")==null,
                    "REFUND_EXECUTION_RELEASE_UNSAFE");
            require(!settle.hasRefundForAfterSale(as),"REFUND_EXECUTION_RELEASE_UNSAFE");
            change(head,"ABORTED",null,null,null);
            return null;
        });
    }
    private void capacity(OrdSubOrder sub,List<OrdItem> items,RefundPlan plan) {
        require(plan.freightMinor()>=0 && plan.totalMinor()>0,"REFUND_EXECUTION_INPUT_INVALID");
        var shares=LineShares.of(sub,items);
        Map<Long,Long> reservedMoney=new HashMap<>();
        Map<Long,Long> reservedQty=new HashMap<>();
        long reservedFreight=0;
        long reservedTotal=0;
        for (var open:store.open(sub.getSubOrderNo())) {
            var prior=snapshot(open).plan();
            reservedFreight=Math.addExact(reservedFreight,prior.freightMinor());
            reservedTotal=Math.addExact(reservedTotal,prior.totalMinor());
            for (var line:prior.lines()) {
                reservedMoney.merge(line.itemId(),line.amountMinor(),Math::addExact);
                reservedQty.merge(line.itemId(),(long)line.qty(),Math::addExact);
            }
        }
        var ids=new HashSet<Long>();
        for (var line:plan.lines()) {
            require(line.qty()>=0 && line.amountMinor()>=0 && ids.add(line.itemId()),"REFUND_EXECUTION_LINES_INVALID");
            var item=items.stream().filter(i -> i.getId()==line.itemId()).findFirst().orElse(null);
            require(item!=null,"REFUND_EXECUTION_LINES_INVALID");
            require(Math.addExact(Math.addExact((long)nz(item.getRefundedQty()),line.qty()),
                    reservedQty.getOrDefault(line.itemId(),0L))<=item.getQty(),"REFUND_EXECUTION_CAPACITY_EXCEEDED");
            require(Math.addExact(Math.addExact(nz(item.getRefundedMinor()),line.amountMinor()),
                    reservedMoney.getOrDefault(line.itemId(),0L))<=shares.get(line.itemId()),
                    "REFUND_EXECUTION_CAPACITY_EXCEEDED");
        }
        long goodsRefunded=items.stream().mapToLong(i -> nz(i.getRefundedMinor())).sum();
        long freightRefunded=Math.subtractExact(nz(sub.getRefundedMinor()),goodsRefunded);
        require(freightRefunded>=0 && Math.addExact(Math.addExact(freightRefunded,reservedFreight),plan.freightMinor())
                <=nz(sub.getFreightAmount()),"REFUND_EXECUTION_CAPACITY_EXCEEDED");
        require(Math.addExact(Math.addExact(nz(sub.getRefundedMinor()),reservedTotal),plan.totalMinor())
                <=nz(sub.getPayAmount()),"REFUND_EXECUTION_CAPACITY_EXCEEDED");
    }
    private void verified(Map<String,Object> head) {
        var proof=settle.refundEvidence((String)head.get("cash_payment_no"));
        require(proof!=null && "SUCCESS".equals(proof.status()) && proof.verifiedAt()!=null
                && proof.verifiedMinor()!=null && proof.providerNo()!=null,"REFUND_CASH_NOT_CONFIRMED");
        long net=number(head,"net_cash_minor");
        matchingIntent(head,proof,net);
        require(proof.verifiedMinor()==net,"REFUND_CASH_EVIDENCE_MISMATCH");
    }
    private void matchingIntent(Map<String,Object> head,RefundEvidence proof,long net) {
        var original=snapshot(head);
        require(proof!=null && "REFUND".equals(proof.direction())
                && original.afterSaleNo().equals(proof.afterSaleNo())
                && original.plan().subOrderNo().equals(proof.subOrderNo()) && original.userNo().equals(proof.userNo())
                && original.currency().equals(proof.currency()) && proof.amountMinor()==net,
                "REFUND_CASH_EVIDENCE_MISMATCH");
    }
    private Map<String,Object> locked(String as) {
        var initial=store.execution(as);
        require(initial!=null,"REFUND_EXECUTION_UNKNOWN");
        store.lockSub((String)initial.get("sub_order_no"));
        return store.lockExecution(as);
    }
    private void change(Map<String,Object> head,String next,String payment,Long net,String result) {
        require(store.change((String)head.get("after_sale_no"),number(head,"version"),(String)head.get("state"),
                next,payment,net,result)==1,"REFUND_EXECUTION_PHASE_CONFLICT");
    }
    private Snapshot snapshot(Map<String,Object> row) {
        return json.readValue((String)row.get("payload_json"),Snapshot.class);
    }
    private Preparation preparation(Map<String,Object> row) {
        var saved=snapshot(row);
        return new Preparation(saved.afterSaleNo(),saved.plan().subOrderNo(),(String)row.get("input_hash"),
                (String)row.get("state"),saved.plan().totalMinor());
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    private long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    private long nz(Long value) { return value==null?0:value; }
    private int nz(Integer value) { return value==null?0:value; }
    private void require(boolean valid,String message) {
        if (!valid) { throw new IllegalArgumentException(message); }
    }
    /** Source subject and refund plan are preserved independently of mutable product and payment settings. */
    public record Snapshot(String afterSaleNo,String orderNo,String userNo,String storeNo,String currency,
                           RefundPlan plan) { }
}

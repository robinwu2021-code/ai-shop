package ai.neargo.shop.invbridge;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.spi.inventory.InventoryReservationPort;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.trade.entity.OrdIndustryOrderSource;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.IndustryOrderSourceMapper;
import ai.neargo.shop.trade.order.mapper.OrderItemMapper;
import ai.neargo.shop.trade.order.mapper.PaymentMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Original fulfillment is replayable; an unpaid lifecycle-3 completion must never acknowledge a payment. */
@Component
public class FulfillmentStockIssueConsumer implements OutboxConsumer {
    private final StockPort stock;
    private final ObjectMapper json;
    private final StoreOrderMapper children;
    private final PaymentMapper parents;
    private final IndustryOrderSourceMapper sources;
    private final OrderItemMapper items;
    private final ObjectProvider<InventoryReservationPort> inventory;
    public FulfillmentStockIssueConsumer(StockPort stock,ObjectMapper json,StoreOrderMapper children,
            PaymentMapper parents,IndustryOrderSourceMapper sources,OrderItemMapper items,
            ObjectProvider<InventoryReservationPort> inventory) {
        this.stock=stock; this.json=json; this.children=children; this.parents=parents;
        this.sources=sources; this.items=items; this.inventory=inventory;
    }
    private ai.neargo.shop.trade.stock.IndustryStockOwnerRouter ownerRouting;
    @org.springframework.beans.factory.annotation.Autowired
    public void routeIndustryStock(ai.neargo.shop.trade.stock.IndustryStockOwnerRouter router){this.ownerRouting=router;}
    @Override public boolean externalWorkflow(){return true;}
    @Override public String handlerId() { return "fulfillment-stock-issue"; }
    @Override public boolean supports(String eventType) {
        return "SUB_ORDER_SHIPPED".equals(eventType) || "SUB_ORDER_COMPLETED".equals(eventType);
    }
    @Override public void consume(SysOutbox event) {
        var payload=json.readTree(event.getPayload());
        String subNo=payload.path("subOrderNo").asString("");
        String orderNo=payload.path("orderNo").asString("");
        if(subNo.isBlank() || orderNo.isBlank()) invalid();
        var sub=DataScopeContext.executeWithoutScope(() -> children.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getSubOrderNo,subNo).eq(OrdSubOrder::getOrderNo,orderNo)));
        if(sub==null || sub.getTenantNo()==null) invalid();
        var parent=DataScopeContext.executeWithoutScope(() -> parents.selectOne(Wrappers.<OrdOrder>lambdaQuery()
                .eq(OrdOrder::getTenantNo,sub.getTenantNo()).eq(OrdOrder::getOrderNo,orderNo)));
        if(parent==null) invalid();
        var exactSource=sub.getEntityNo()==null||sub.getStoreNo()==null?null:DataScopeContext.executeWithoutScope(()->sources.selectOne(
                Wrappers.<OrdIndustryOrderSource>lambdaQuery().eq(OrdIndustryOrderSource::getTenantNo,sub.getTenantNo())
                        .eq(OrdIndustryOrderSource::getEntityNo,sub.getEntityNo()).eq(OrdIndustryOrderSource::getStoreNo,sub.getStoreNo())
                        .eq(OrdIndustryOrderSource::getOrderNo,orderNo).eq(OrdIndustryOrderSource::getSubOrderNo,subNo)));
        if(exactSource!=null&&("FOOD_LOCAL".equals(exactSource.getInventoryOwner())||exactSource.getDemandPlanSnapshot()!=null)) {
            acknowledgeExact(exactSource,sub);return;
        }
        if(!Integer.valueOf(3).equals(sub.getInventoryLifecycleVersion())) {
            if(!OrdOrder.PAID.equals(parent.getStatus())) invalid();
            stock.allocatePaid(orderNo); stock.issue(subNo,orderNo); return;
        }
        if(sub.getEntityNo()==null || sub.getStoreNo()==null || sub.getStatus()==null
                || !Set.of(OrdSubOrder.FULFILLING,OrdSubOrder.COMPLETED).contains(sub.getStatus())) invalid();
        var source=exactSource;
        if(source==null || !"POSTPAY".equals(source.getOrderMode()) || !"ACCEPTED".equals(source.getStatus())) invalid();
        boolean paid=OrdOrder.PAID.equals(parent.getStatus()) && parent.getPaidAt()!=null;
        if(!paid && (!OrdOrder.WAIT_PAY.equals(parent.getStatus()) || parent.getPaidAt()!=null)) invalid();
        var candidates=inventory.orderedStream().limit(2).toList(); if(candidates.size()!=1) invalid();
        var port=candidates.getFirst();
        var scope=new InventoryReservationPort.Scope(sub.getTenantNo(),sub.getEntityNo(),orderNo);
        var expected=original(scope,sub);
        var actual=port.query(scope); verify(scope,expected,actual,paid);
        if(paid) {
            actual=port.allocatePaid(scope); verify(scope,expected,actual,true);
            if(!actual.paymentAcknowledged()) invalid();
        } else if(actual.paymentAcknowledged()) invalid();
        if(actual.state()==InventoryReservationPort.State.ISSUED_UNPAID
                || actual.state()==InventoryReservationPort.State.COMMITTED) return;
        var issued=port.issue(scope,subNo); verify(scope,expected,issued,paid);
        if(issued.paymentAcknowledged()!=paid || (paid?issued.state()!=InventoryReservationPort.State.COMMITTED
                :issued.state()!=InventoryReservationPort.State.ISSUED_UNPAID)) invalid();
    }
    private void acknowledgeExact(OrdIndustryOrderSource source,OrdSubOrder sub) {
        if(ownerRouting==null||source.getDemandPlanSnapshot()==null||!"ACCEPTED".equals(source.getStatus())
                ||!Set.of(OrdSubOrder.FULFILLING,OrdSubOrder.COMPLETED).contains(sub.getStatus()))invalid();
        var plan=ai.neargo.shop.spi.inventory.InventoryDemandPlanFacts.validate(json.readValue(source.getDemandPlanSnapshot(),ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan.class));
        if(!plan.planNo().equals(source.getDemandPlanNo())||!plan.planHash().equals(source.getDemandPlanHash())
                ||!sub.getTenantNo().equals(plan.scope().tenantNo())||!sub.getEntityNo().equals(plan.scope().entityNo())
                ||plan.branches().stream().anyMatch(b->!sub.getStoreNo().equals(b.storeNo())||!sub.getSubOrderNo().equals(b.fulfillmentRef())))invalid();
        int protocol=Set.of("POSTPAY","CHANNEL").contains(source.getOrderMode())?3:2;
        var proof=ownerRouting.exact(source.getInventoryOwner(),plan,protocol).query(plan.scope());
        if(proof==null||!plan.scope().equals(proof.scope())||!plan.planNo().equals(proof.planNo())
                ||!plan.planHash().equals(proof.planHash())||proof.lifecycleVersion()!=protocol
                ||!Set.of("ACCEPTED_UNPAID","ALLOCATED").contains(proof.status()))invalid();
        var expected=plan.branches().stream().flatMap(b->b.leaves().stream()).toList();
        if(proof.lines().size()!=expected.size()||proof.lines().stream().map(ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Progress::leafRef).distinct().count()!=expected.size())invalid();
        for(var leaf:expected){var progress=proof.lines().stream().filter(l->leaf.leafRef().equals(l.leafRef())).findFirst().orElseThrow(()->new IllegalStateException("Missing original local leaf"));
            if(!leaf.itemRef().equals(progress.itemRef())||!leaf.unit().equals(progress.unit())||!leaf.definitionHash().equals(progress.definitionHash())
                    ||progress.issuedQty()==null||progress.releasedQty()==null||progress.issuedQty().signum()<0||progress.releasedQty().signum()<0
                    ||progress.issuedQty().add(progress.releasedQty()).compareTo(leaf.quantity())!=0)invalid();
        }
        // Exact fulfillment already owns these deductions. This callback verifies; it never posts a second issue.
    }
    private List<InventoryReservationPort.ReservedLine> original(InventoryReservationPort.Scope scope,OrdSubOrder sub) {
        var quantities=new LinkedHashMap<String,Integer>();
        var rows=DataScopeContext.executeWithoutScope(() -> items.selectList(Wrappers.<OrdItem>lambdaQuery()
                .eq(OrdItem::getTenantNo,scope.tenantNo()).eq(OrdItem::getOrderNo,scope.externalRef())
                .eq(OrdItem::getSubOrderNo,sub.getSubOrderNo())));
        if(rows.isEmpty()) invalid();
        for(var row:rows) {
            if(row.getSkuNo()==null || row.getQty()==null || row.getQty()<=0) invalid();
            quantities.merge(row.getSkuNo(),row.getQty(),Math::addExact);
        }
        return quantities.entrySet().stream().map(e -> new InventoryReservationPort.ReservedLine(e.getKey(),
                e.getValue(),sub.getSubOrderNo())).sorted(Comparator.comparing(
                InventoryReservationPort.ReservedLine::skuNo)).toList();
    }
    private void verify(InventoryReservationPort.Scope scope,List<InventoryReservationPort.ReservedLine> expected,
            InventoryReservationPort.Snapshot actual,boolean paid) {
        if(actual==null || !scope.equals(actual.scope()) || actual.lifecycleVersion()!=3
                || !expected.equals(actual.originalLines().stream().sorted(Comparator.comparing(
                InventoryReservationPort.ReservedLine::skuNo)).toList())) invalid();
        Set<InventoryReservationPort.State> states=paid?Set.of(InventoryReservationPort.State.ACCEPTED_UNPAID,
                InventoryReservationPort.State.PARTIAL_UNPAID,InventoryReservationPort.State.ISSUED_UNPAID,
                InventoryReservationPort.State.ALLOCATED,InventoryReservationPort.State.PARTIALLY_COMMITTED,
                InventoryReservationPort.State.COMMITTED):Set.of(InventoryReservationPort.State.ACCEPTED_UNPAID,
                InventoryReservationPort.State.PARTIAL_UNPAID,InventoryReservationPort.State.ISSUED_UNPAID);
        if(!states.contains(actual.state()) || !paid && actual.paymentAcknowledged()) invalid();
    }
    private static void invalid() { throw new IllegalStateException("STOCK_FULFILLMENT_OWNER_PROOF_INVALID"); }
}

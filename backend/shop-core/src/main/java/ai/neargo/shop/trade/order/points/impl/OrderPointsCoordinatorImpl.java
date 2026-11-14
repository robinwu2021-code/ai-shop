package ai.neargo.shop.trade.order.points.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.points.*;
import ai.neargo.shop.trade.order.mapper.OrderPointsBindingMapper;
import ai.neargo.shop.trade.order.points.*;
import ai.neargo.shop.trade.order.points.OrderPointsBindingService.*;
import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService.*;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

@Service
public class OrderPointsCoordinatorImpl
        implements OrderPointsCoordinator, DetachmentGuard, CompletionGuard {
    private final PointsIntegrationPolicyService policies;
    private final OrderPointsBindingService bindings;
    private final OrderPointsBindingMapper mapper;
    private final ObjectProvider<PointsRedeemPort> redeem;
    private final ObjectProvider<PointsFactPort> facts;
    private final ObjectProvider<PointsObligationPort> obligations;
    private final ObjectProvider<OrderPointsSourcePort> source;
    private final ObjectMapper json = new ObjectMapper();
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(OrderPointsCoordinatorImpl.class);

    public OrderPointsCoordinatorImpl(
            PointsIntegrationPolicyService policies,
            OrderPointsBindingService bindings,
            OrderPointsBindingMapper mapper,
            ObjectProvider<PointsRedeemPort> redeem,
            ObjectProvider<PointsFactPort> facts,
            ObjectProvider<PointsObligationPort> obligations,
            ObjectProvider<OrderPointsSourcePort> source) {
        this.policies = policies;
        this.bindings = bindings;
        this.mapper = mapper;
        this.redeem = redeem;
        this.facts = facts;
        this.obligations = obligations;
        this.source = source;
    }

    private ObjectProvider<PointsOperationsPort> operations;
    private ObjectProvider<PointsRefundPort> refundProofs;
    @org.springframework.beans.factory.annotation.Autowired
    public void setRefundProofs(ObjectProvider<PointsRefundPort> refunds) { this.refundProofs=refunds; }
    @org.springframework.beans.factory.annotation.Autowired
    public void setUnifiedOperations(ObjectProvider<PointsOperationsPort> operations) { this.operations = operations; }

    @Override
    public Decision decide(Context c) {
        require(c != null, "POINTS_INTEGRATION_INPUT_INVALID");
        var p = policies.policy(c.storeNo(), c.scene());
        boolean active = p.mode() == Mode.ACTIVE;
        boolean r = active && p.redeemEnabled() && redeem.getIfAvailable() != null;
        boolean e = active && p.earnEnabled() && facts.getIfAvailable() != null;
        boolean canRedeem = c.wantsRedeem() && r;
        boolean canEarn = c.wantsEarn() && e;
        if (c.confirmedPointsPrice() && !canRedeem)
            return new Decision(
                    Action.RECONFIRM, p.version(), false, false, "POINTS_REQUOTE_REQUIRED");
        boolean ready = canRedeem || canEarn;
        return new Decision(
                ready ? Action.READY : Action.BYPASS,
                p.version(),
                canEarn,
                canRedeem,
                ready
                        ? null
                        : active
                                ? "POINTS_IMPLEMENTATION_UNAVAILABLE"
                                : "POINTS_INTEGRATION_DETACHED");
    }

    @Override
    public Binding admit(Admission c) {
        require(
                c != null
                        && (!c.redemption() || redeem.getIfAvailable() != null)
                        && (!c.earning() || facts.getIfAvailable() != null),
                "POINTS_IMPLEMENTATION_REQUIRED");
        return bindings.admit(c);
    }

    @Override
    public Binding resume(String id) {
        String body = DataScopeContext.executeWithoutScope(() -> mapper.bindingByRequest(id));
        require(body != null, "POINTS_BINDING_UNKNOWN");
        return json.readValue(body, Binding.class);
    }

    @Override
    public void onSourceEvent(PointsOrderEvent event) {
        require(event != null, "POINTS_ORDER_EVENT_INVALID");
        var binding = bindings.find(event.subOrderNo());
        require(binding != null, "POINTS_BINDING_UNKNOWN");
        var a = binding.admission();
        require(
                a.orderNo().equals(event.orderNo())
                        && a.userNo().equals(event.userNo())
                        && a.storeNo().equals(event.storeNo())
                        && a.merchantNo().equals(event.merchantNo())
                        && a.typeCode().equals(event.typeCode())
                        && a.currency().equals(event.currency()),
                "POINTS_BINDING_EVENT_CONFLICT");
        var port = facts.getIfAvailable();
        require(port != null, "POINTS_IMPLEMENTATION_REQUIRED");
        var f = event.fact();
        var frozen = json.readValue(a.snapshot().ruleSnapshot(), PointsOrderPlacementCoordinator.EarningSnapshot.class);
        if(frozen.schemaVersion()==3 && f.earning()==null) {
            var unified=operations==null?null:operations.getIfAvailable();
            require(unified!=null,"POINTS_IMPLEMENTATION_REQUIRED");
            String business=a.subOrderNo();
            if(f.zeroCash()!=null)business=f.zeroCash().afterSaleNo();
            if(f.refund()!=null) {
                var proofs=refundProofs==null?null:refundProofs.getIfAvailable();
                require(proofs!=null,"POINTS_IMPLEMENTATION_REQUIRED");
                var confirmation=f.refund().confirmation();
                var proof=proofs.recoveryStatus(confirmation.preparationNo(),confirmation.inputHash());
                require(proof!=null&&a.subOrderNo().equals(proof.subOrderNo()),"POINTS_REFUND_SOURCE_CONFLICT");
                business=proof.afterSaleNo();
            }
            String digest;
            try {digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(event.eventNo().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
            catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
            var context=new PointsOperationsPort.Context("TRADE:FACT:"+digest,
                new PointsOperationsPort.Operation(PointsOperationsPort.Source.TRADE,business,"FACT:"+digest.substring(0,32),a.typeCode()),a.currency());
            if(f.lifecycle()!=null)unified.acceptLifecycle(context,f.lifecycle());
            else if(f.closure()!=null)unified.acceptClosure(context,f.closure());
            else if(f.zeroCash()!=null)unified.acceptZeroCash(context,f.zeroCash());
            else unified.acceptRefundFact(context,f.refund());
            return;
        }
        if (f.earning() != null) {
            require(a.earning(), "POINTS_EARNING_NOT_ADMITTED");
            var snapshot = json.readValue(a.snapshot().ruleSnapshot(), PointsOrderPlacementCoordinator.EarningSnapshot.class);
            if (snapshot.schemaVersion() >= 3) {
                require(snapshot.schemaVersion() == 3 && snapshot.operationNo() != null, "POINTS_EARNING_SCHEMA_UNSUPPORTED");
                var unified = operations == null ? null : operations.getIfAvailable();
                require(unified != null, "POINTS_IMPLEMENTATION_REQUIRED");
                var original = source.getIfAvailable();
                require(original != null, "POINTS_PAID_PROOF_REQUIRED");
                var paidEvent = original.find("ORDER:PAID:" + a.subOrderNo())
                        .orElseThrow(() -> new IllegalArgumentException("POINTS_PAID_PROOF_REQUIRED"));
                require(paidEvent.orderNo().equals(a.orderNo()) && paidEvent.typeCode().equals(a.typeCode())
                        && paidEvent.storeNo().equals(a.storeNo()) && paidEvent.merchantNo().equals(a.merchantNo()),
                        "POINTS_PAID_PROOF_REQUIRED");
                var op = new PointsOperationsPort.Operation(PointsOperationsPort.Source.TRADE, a.subOrderNo(), snapshot.operationNo(), a.typeCode());
                var context = new PointsOperationsPort.Context("TRADE:EARN:" + binding.bindingNo(), op, a.currency());
                unified.submitEarning(context, new PointsOperationsPort.Earning(f.earning(), paidEvent.fact().lifecycle()));
            } else port.acceptEarning(f.earning());
        } else if (f.lifecycle() != null) port.accept(f.lifecycle());
        else if (f.closure() != null) port.acceptClosure(f.closure());
        else if (f.zeroCash() != null) port.acceptZeroCash(f.zeroCash());
        else port.acceptRefundConfirmation(f.refund());
    }

    @Override
    public StoreObligations inspectStore(String store) {
        if(store==null || store.isBlank()) throw new IllegalArgumentException("POINTS_STORE_REQUIRED");
        try {
            long active=DataScopeContext.executeWithoutScope(() -> mapper.activePolicies(store));
            long open=DataScopeContext.executeWithoutScope(() -> mapper.openBindings(store));
            long count=DataScopeContext.executeWithoutScope(() -> mapper.bindingCount(store));
            var pay=obligations.getIfAvailable();
            var proof=pay==null?PointsObligationPort.Obligations.unknown():pay.inspect(store);
            var journal=source.getIfAvailable();
            boolean sourceKnown=count==0 || journal!=null;
            boolean settled=count==0 || (journal!=null && journal.settled(store));
            if(proof==null) proof=PointsObligationPort.Obligations.unknown();
            return new StoreObligations(sourceKnown && proof.known(),active,open,settled,proof);
        } catch(RuntimeException unavailable) {
            log.warn("Points store inspection unavailable: {}",unavailable.getClass().getSimpleName());
            return new StoreObligations(false,-1,-1,false,PointsObligationPort.Obligations.unknown());
        }
    }

    @Override
    public boolean canDetach(String store) {
        try {
            if (DataScopeContext.executeWithoutScope(() -> mapper.activePolicies(store)) != 0)
                return false;
            if (DataScopeContext.executeWithoutScope(() -> mapper.openBindings(store)) != 0)
                return false;
            var pay = obligations.getIfAvailable();
            if (pay == null || !pay.inspect(store).clear()) return false;
            if (DataScopeContext.executeWithoutScope(() -> mapper.bindingCount(store)) == 0)
                return true;
            var journal = source.getIfAvailable();
            return journal != null && journal.settled(store);
        } catch (RuntimeException unavailable) {
            log.warn(
                    "Points detachment inspection unavailable: {}",
                    unavailable.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public boolean complete(Binding b) {
        var pay = obligations.getIfAvailable();
        var journal = source.getIfAvailable();
        return pay != null
                && journal != null
                && pay.inspectSubOrder(b.admission().subOrderNo()).clear()
                && journal.settledSubOrder(b.admission().subOrderNo());
    }

    private static void require(boolean v, String error) {
        if (!v) throw new IllegalArgumentException(error);
    }
}

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
        if (f.earning() != null) {
            require(a.earning(), "POINTS_EARNING_NOT_ADMITTED");
            port.acceptEarning(f.earning());
        } else if (f.lifecycle() != null) port.accept(f.lifecycle());
        else if (f.closure() != null) port.acceptClosure(f.closure());
        else if (f.zeroCash() != null) port.acceptZeroCash(f.zeroCash());
        else port.acceptRefundConfirmation(f.refund());
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

package ai.neargo.commerce.core.mall;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.mall.*;

import java.time.Instant;
import java.util.*;

/** Immutable business snapshots shared by order settlement/fulfillment, never host objects. */
public final class MallSnapshot {
    private MallSnapshot() {}

    public record CreditBalance(long version, long available) {}

    public record CreditLot(String lotNo, long version, long available, Long expiresAt) {}

    public record CreditUse(String lotNo, long version, long amount) {}

    public record BasePlan(String planNo, PlanTier tier) {}

    public record Sources(String currency, List<BasePlan> basePlans, Set<String> permanentCodes) {
        public Sources {
            basePlans = List.copyOf(basePlans);
            permanentCodes = Set.copyOf(permanentCodes);
        }
    }

    public record Component(
            ComponentAllocation.Allocation allocation, PlanDraft plan, Map<String, String> ends) {
        public Component {
            ends = Map.copyOf(ends);
        }
    }

    public record OrderComponent(
            String orderComponentNo,
            String orderItemNo,
            Component snapshot,
            String fulfillmentStatus,
            long version) {}

    public record ShipmentItem(String orderComponentNo, String hardwareSkuNo, int quantity) {}

    public record Line(
            SkuDraft sku,
            int quantity,
            List<Component> components,
            long skuVersion,
            long skuDefinitionVersion,
            long productVersion) {
        public Line {
            components = List.copyOf(components);
        }
    }

    public record Quote(
            String quoteNo,
            StoreRef store,
            String entityNo,
            String buyerNo,
            String businessType,
            String intent,
            String currency,
            long valuationAt,
            long expiresAt,
            long entitlementVersion,
            long accountVersion,
            long balanceVersion,
            long catalogVersion,
            long businessTypeVersion,
            String pricingPolicyVersion,
            String stackingPolicyVersion,
            List<Line> lines,
            List<CreditUse> creditUses) {
        public Quote {
            lines = List.copyOf(lines);
            creditUses = List.copyOf(creditUses);
        }

        public long gross() {
            return lines.stream()
                    .mapToLong(l -> Math.multiplyExact(l.sku().listPrice().minor(), l.quantity()))
                    .reduce(0, Math::addExact);
        }

        public long deal() {
            return lines.stream()
                    .mapToLong(l -> Math.multiplyExact(l.sku().price().minor(), l.quantity()))
                    .reduce(0, Math::addExact);
        }

        public long credit() {
            return creditUses.stream().mapToLong(CreditUse::amount).reduce(0, Math::addExact);
        }

        public boolean digital() {
            return lines.stream()
                    .flatMap(l -> l.components().stream())
                    .anyMatch(c -> c.allocation().component().kind() != ComponentKind.HARDWARE);
        }

        public boolean hardware() {
            return lines.stream()
                    .flatMap(l -> l.components().stream())
                    .anyMatch(c -> c.allocation().component().kind() == ComponentKind.HARDWARE);
        }

        public List<QuoteComponent> components() {
            return lines.stream()
                    .flatMap(l -> l.components().stream())
                    .map(
                            c -> {
                                var a = c.allocation();
                                var t = a.component();
                                return new QuoteComponent(
                                        t.componentNo(),
                                        t.kind(),
                                        new Money(currency, a.gross()),
                                        new Money(currency, a.deal()),
                                        new Money(currency, a.credit()),
                                        new Money(currency, a.cash()),
                                        t.periodDays() == null ? 0 : t.periodDays(),
                                        a.cycles().size());
                            })
                    .toList();
        }

        public QuoteView view() {
            return new QuoteView(
                    quoteNo,
                    store,
                    new Money(currency, deal()),
                    new Money(currency, credit()),
                    new Money(currency, deal() - credit()),
                    components(),
                    entitlementVersion,
                    balanceVersion,
                    Instant.ofEpochMilli(expiresAt));
        }
    }

    public record Order(
            String orderNo,
            Quote quote,
            String idempotencyKey,
            String requestHash,
            ShippingAddress shippingAddress,
            OrderState.Axes axes,
            long createdAt,
            long version) {
        public OrderView view() {
            return new OrderView(
                    orderNo,
                    quote.store(),
                    status(),
                    new Money(quote.currency(), quote.deal() - quote.credit()),
                    quote.components(),
                    Instant.ofEpochMilli(createdAt));
        }

        public OrderStatus status() {
            return axes.status();
        }

        public Order withAxes(OrderState.Axes s) {
            return new Order(
                    orderNo,
                    quote,
                    idempotencyKey,
                    requestHash,
                    shippingAddress,
                    s,
                    createdAt,
                    Math.addExact(version, 1));
        }
    }
}

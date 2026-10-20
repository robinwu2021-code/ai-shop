package ai.neargo.shop.trade.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort.GrantCommand;
import ai.neargo.shop.spi.entitlement.EntitlementGrantSourcePort;
import ai.neargo.shop.spi.entitlement.EntitlementSaleSourcePort;
import ai.neargo.shop.spi.entitlement.PaidCardSalePlanPort;
import java.util.List;
import ai.neargo.shop.spi.entitlement.EntitlementSaleReservationPort.ReserveSale;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entitlement.entity.OrdCardSaleSnapshot;
import ai.neargo.shop.trade.mapper.TradeMappers.CardSaleSnapshotMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Source-owner reads only: no payment calls or resource locks cross the core/pay boundary. */
@Component
public class CardSaleGrantSourcePortImpl implements EntitlementGrantSourcePort, EntitlementSaleSourcePort, PaidCardSalePlanPort {
    private final CardSaleSnapshotMapper snapshots;
    private final OrderMapper orders;
    private final SourceDecisionPort decisions;

    public CardSaleGrantSourcePortImpl(CardSaleSnapshotMapper snapshots, OrderMapper orders,
                                       SourceDecisionPort decisions) {
        this.snapshots = snapshots;
        this.orders = orders;
        this.decisions = decisions;
    }

    @Override public List<SaleUnit> units(EntitlementContext ctx, String orderNo) {
        var rows = DataScopeContext.executeWithoutScope(() -> snapshots.selectList(
                Wrappers.<OrdCardSaleSnapshot>lambdaQuery().eq(OrdCardSaleSnapshot::getTenantNo,ctx.tenantNo())
                        .eq(OrdCardSaleSnapshot::getOrderNo,orderNo)
                        .orderByAsc(OrdCardSaleSnapshot::getOrderLineNo,OrdCardSaleSnapshot::getUnitIndex,
                                OrdCardSaleSnapshot::getEntitlementIndex)));
        return rows.stream().map(row -> {
            var source = new SourceRef("CORE","PAID_CARD_ORDER",row.getOrderNo(),row.getOrderLineNo(),
                    row.getUnitIndex(),row.getEntitlementIndex(),0);
            snapshot(ctx,source);
            return new SaleUnit(new ReserveSale(ReserveSale.number(source),source,row.getTemplateVersionNo(),
                    row.getUserNo(),row.getEntityNo(),row.getSnapshotHash()),row.getPaidFactNo());
        }).toList();
    }

    @Override public boolean supports(SourceRef source) {
        return "CORE".equals(source.sourceSystem()) && "PAID_CARD_ORDER".equals(source.sourceType());
    }

    @Override public SaleSourceFact saleSource(EntitlementContext ctx, ReserveSale c) {
        var row = snapshot(ctx, c.sourceRef());
        if (!Objects.equals(row.getUserNo(), c.userNo()) || !Objects.equals(row.getEntityNo(), c.entityNo())
                || !Objects.equals(row.getTemplateVersionNo(), c.templateVersionNo())
                || !Objects.equals(row.getSnapshotHash(), c.benefitSnapshotHash())) conflict();
        var order = order(ctx, row);
        if (order == null || !Objects.equals(ctx.tenantNo(), order.getTenantNo())
                || !Objects.equals(row.getOrderNo(), order.getOrderNo())
                || !Objects.equals(row.getUserNo(), order.getUserNo())
                || !Objects.equals(row.getCurrency(), order.getCurrency())) conflict();
        var truth = decision(ctx, c.sourceRef(), row);
        boolean available = truth.status() == SourceDecisionPort.State.PENDING
                && Set.of(OrdOrder.WAIT_PAY, OrdOrder.WAIT_OFFLINE_PAY).contains(order.getStatus())
                && order.getPayAmount() != null && order.getPayAmount() > 0
                && (order.getPaidAt() == null || order.getPaidAt() <= 0)
                && row.getPaidAt() == null && row.getPaidFactNo() == null
                && order.getPayDeadlineAt() != null && order.getPayDeadlineAt() > System.currentTimeMillis();
        return new SaleSourceFact(c, row.getCurrency(), row.getRuleSnapshot(), available, truth);
    }

    @Override public SourceFact resolve(EntitlementContext ctx, GrantCommand command) {
        var row = snapshot(ctx, command.sourceRef());
        if (!Objects.equals(row.getUserNo(), command.recipientUserNo())
                || !Objects.equals(row.getEntityNo(), command.entityNo())
                || !Objects.equals(row.getTemplateVersionNo(), command.templateVersionNo())
                || !Objects.equals(row.getSnapshotHash(), command.benefitSnapshotHash())
                || !Objects.equals(row.getPaidFactNo(), command.paidFactRef())
                || command.quantity() != 1 || command.permit() != null) conflict();
        var truth = decision(ctx, command.sourceRef(), row);
        if (truth.status() != SourceDecisionPort.State.COMMITTED) {
            throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        }
        return new SourceFact(ctx.tenantNo(), command, truth.decision().occurredAt());
    }

    @Override public SourceDecisionPort.SourceDecision decision(EntitlementContext ctx, SourceRef source) {
        return decision(ctx, source, snapshot(ctx, source));
    }

    private SourceDecisionPort.SourceDecision decision(EntitlementContext ctx, SourceRef source,
                                                       OrdCardSaleSnapshot row) {
        var recorded = decisions.query(ctx, source);
        if (recorded == null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if (recorded.status() == SourceDecisionPort.State.CANCELLED) {
            var order = order(ctx, row);
            if (order == null || !Set.of(OrdOrder.CANCELLED, OrdOrder.CLOSED).contains(order.getStatus())
                    || order.getPaidAt() != null && order.getPaidAt() > 0
                    || row.getPaidAt() != null || row.getPaidFactNo() != null
                    || !Objects.equals(ctx.tenantNo(), order.getTenantNo())
                    || !Objects.equals(row.getOrderNo(), order.getOrderNo())
                    || !Objects.equals(row.getUserNo(), order.getUserNo())
                    || !Objects.equals(row.getCurrency(), order.getCurrency())
                    || !source.equals(recorded.source())
                    || recorded.decision().decisionType() != DecisionRef.DecisionType.GRANT_ABORT
                    || !Objects.equals(row.getUserNo(), recorded.userNo())
                    || !Objects.equals(row.getEntityNo(), recorded.entityNo())
                    || !Objects.equals(row.getStoreNo(), recorded.storeNo())
                    || !Objects.equals(row.getCurrency(), recorded.currency())
                    || !Objects.equals(row.getUnitPriceMinor(), recorded.amountMinor())
                    || !Objects.equals(row.getSnapshotHash(), recorded.snapshotHash())) conflict();
            return recorded;
        }
        if (!paid(ctx, row)) {
            if (recorded.status() == SourceDecisionPort.State.COMMITTED) conflict();
            return new SourceDecisionPort.SourceDecision(source, SourceDecisionPort.State.PENDING,
                    null, row.getUserNo(), row.getEntityNo(), row.getStoreNo(), row.getCurrency(),
                    row.getUnitPriceMinor(), row.getSnapshotHash());
        }
        var decision = new DecisionRef("CS" + source.grantKey().substring(0, 60),
                row.getPaidAt().toInstant(ZoneOffset.UTC), source.sourceRevision(),
                DecisionRef.DecisionType.GRANT_COMMIT);
        var success = new SourceDecisionPort.SourceDecision(source, SourceDecisionPort.State.COMMITTED, decision,
                row.getUserNo(), row.getEntityNo(), row.getStoreNo(), row.getCurrency(), row.getUnitPriceMinor(),
                row.getSnapshotHash());
        if (recorded.status() == SourceDecisionPort.State.COMMITTED && !recorded.equals(success)) conflict();
        return success;
    }

    private OrdCardSaleSnapshot snapshot(EntitlementContext ctx, SourceRef source) {
        if (!supports(source) || source.sourceRevision() != 0) conflict();
        var row = DataScopeContext.executeWithoutScope(() -> snapshots.findBySource(ctx.tenantNo(), source.grantKey()));
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        if (!Objects.equals(ctx.tenantNo(), row.getTenantNo())
                || !Objects.equals(source.grantKey(), row.getSourceKey())
                || !Objects.equals(source.sourceNo(), row.getOrderNo())
                || !Objects.equals(source.sourceLineNo(), row.getOrderLineNo())
                || !Objects.equals(source.unitIndex(), row.getUnitIndex())
                || !Objects.equals(source.entitlementIndex(), row.getEntitlementIndex())
                || row.getUnitPriceMinor() == null || row.getUnitPriceMinor() <= 0) conflict();
        return row;
    }

    private boolean paid(EntitlementContext ctx, OrdCardSaleSnapshot row) {
        if (row.getPaidAt() == null || row.getPaidFactNo() == null) return false;
        var order = order(ctx, row);
        if (order == null || !OrdOrder.PAID.equals(order.getStatus())) return false;
        // These are the current cash collection paths; new channels require an explicit source-owner integration.
        if (!Set.of("WECHAT", "ALIPAY", "OFFLINE").contains(Objects.toString(order.getPayChannel(), ""))
                || !Objects.equals(ctx.tenantNo(), order.getTenantNo())
                || !Objects.equals(row.getOrderNo(), order.getOrderNo())
                || order.getPayAmount() == null || order.getPayAmount() <= 0
                || !Objects.equals(order.getUserNo(), row.getUserNo())
                || !Objects.equals(order.getCurrency(), row.getCurrency()) || order.getPaidAt() == null
                || row.getPaidAt().toInstant(ZoneOffset.UTC).toEpochMilli() != order.getPaidAt()) conflict();
        String fact = order.getPayTradeNo() == null || order.getPayTradeNo().isBlank()
                ? order.getOrderNo() : order.getPayTradeNo();
        if (!fact.equals(row.getPaidFactNo())) conflict();
        return true;
    }

    private OrdOrder order(EntitlementContext ctx, OrdCardSaleSnapshot row) {
        return DataScopeContext.executeWithoutScope(() -> orders.selectOne(Wrappers.<OrdOrder>lambdaQuery()
                .eq(OrdOrder::getTenantNo, ctx.tenantNo()).eq(OrdOrder::getOrderNo, row.getOrderNo())));
    }

    private static void conflict() { throw BizException.of(ErrorCode.CONFLICT); }
}

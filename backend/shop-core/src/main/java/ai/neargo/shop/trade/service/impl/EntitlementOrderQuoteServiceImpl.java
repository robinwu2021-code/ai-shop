package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.AssetKind;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.QuoteView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseCommand;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort.UseSourceFact;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.trade.dto.AuthorizationCommand;
import ai.neargo.shop.trade.dto.OrderCouponSelection;
import ai.neargo.shop.trade.entitlement.EntitlementServiceSnapshotPolicy;
import ai.neargo.shop.trade.entitlement.entity.OrdEntAuthorization;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.EntitlementAuthorizationMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import ai.neargo.shop.trade.service.EntitlementAuthorizationService;
import ai.neargo.shop.trade.service.EntitlementOrderQuoteService;
import ai.neargo.shop.trade.service.EntitlementOrderUseService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Core owns the original checkout selection and atomically binds consent to its durable usage command. */
@Service
public class EntitlementOrderQuoteServiceImpl implements EntitlementOrderQuoteService {
    private final OrderMapper orders;
    private final SubOrderMapper subs;
    private final EntitlementAuthorizationMapper intents;
    private final EntitlementAuthorizationService authorizations;
    private final EntitlementOrderUseService uses;
    private final ObjectMapper json;
    public EntitlementOrderQuoteServiceImpl(OrderMapper orders, SubOrderMapper subs,
            EntitlementAuthorizationMapper intents, EntitlementAuthorizationService authorizations,
            EntitlementOrderUseService uses, ObjectMapper json) {
        this.orders = orders; this.subs = subs; this.intents = intents;
        this.authorizations = authorizations; this.uses = uses; this.json = json;
    }
    @Override public Draft resolveDraft(String tenant, String user, String draft, long revision) {
        return DataScopeContext.executeWithoutScope(() -> {
            var sub = subs.selectOne(Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getTenantNo, tenant)
                    .eq(OrdSubOrder::getSubOrderNo, draft).eq(OrdSubOrder::getUserNo, user));
            if (sub == null) throw BizException.of(ErrorCode.NOT_FOUND);
            var order = orders.selectOne(Wrappers.<OrdOrder>lambdaQuery().eq(OrdOrder::getTenantNo, tenant)
                    .eq(OrdOrder::getOrderNo, sub.getOrderNo()).eq(OrdOrder::getUserNo, user));
            if (order == null) throw BizException.of(ErrorCode.NOT_FOUND);
            if (!OrdOrder.WAIT_PAY.equals(order.getStatus()) || order.getVersion() != revision) conflict();
            return new Draft(order.getOrderNo(), new SourceRef("CORE", "ORDER_USE", sub.getSubOrderNo(),
                    null, null, null, revision));
        });
    }
    @Override @Transactional
    public void register(EntitlementContext ctx, String order, UseSourceFact source, QuoteView quote, String instance) {
        DataScopeContext.executeWithoutScope(() -> {
            var row = row(ctx.tenantNo(), ctx.actorNo(), order, quote.quoteRef(), true);
            if (row == null || !source.tenantNo().equals(ctx.tenantNo()) || !source.userNo().equals(ctx.actorNo())
                    || !source.source().equals(quote.sourceRef()) || !source.source().equals(readSource(row))
                    || !source.entityNo().equals(row.getEntityNo()) || !source.storeNo().equals(row.getStoreNo())
                    || !source.orderSnapshotHash().equals(row.getOrderSnapshotHash())
                    || !quote.selectionDigest().equals(row.getSelectionDigest())
                    || !quote.currency().equals(row.getCurrency()) || quote.discountMinor() != row.getAmountMinor()
                    || quote.discountMinor() <= 0 || quote.allocations().isEmpty()
                    || quote.allocations().stream().anyMatch(a -> !instance.equals(a.instanceNo()))) conflict();
            String snapshot = json.writeValueAsString(new OrderCouponSelection(order, source, quote, instance));
            if (row.getOrderUseSnapshot() != null) {
                if (!json.readValue(row.getOrderUseSnapshot(), OrderCouponSelection.class).equals(
                        json.readValue(snapshot, OrderCouponSelection.class))) conflict();
                return null;
            }
            if (!"PENDING".equals(row.getStatus())) conflict();
            row.setOrderUseSnapshot(snapshot);
            if (intents.updateById(row) != 1) conflict();
            return null;
        });
    }
    @Override public OrderCouponSelection selection(String tenant, String user, String order, String quote) {
        return DataScopeContext.executeWithoutScope(() -> {
            var row = row(tenant, user, order, quote, false);
            if (row == null || row.getOrderUseSnapshot() == null) return null;
            var selected = json.readValue(row.getOrderUseSnapshot(), OrderCouponSelection.class);
            if (!order.equals(selected.orderNo()) || !user.equals(selected.source().userNo())
                    || !tenant.equals(selected.source().tenantNo()) || !quote.equals(selected.quote().quoteRef())
                    || !selected.source().source().equals(readSource(row))
                    || !selected.source().orderSnapshotHash().equals(row.getOrderSnapshotHash())) conflict();
            return selected;
        });
    }
    @Override @Transactional
    public Prepared authorizeAndStage(EntitlementContext ctx, String order, AuthorizationCommand c) {
        var authorization = authorizations.confirm(ctx.tenantNo(), ctx.actorNo(), order, c);
        var selection = selection(ctx.tenantNo(), ctx.actorNo(), order, c.quoteRef());
        if (selection == null) conflict();
        var source = selection.source(); var quote = selection.quote();
        String operation = "CU" + EntitlementServiceSnapshotPolicy.hash(quote.quoteRef()).substring(0, 60);
        var command = new UseCommand(operation, source.source(), source.userNo(), source.entityNo(), source.storeNo(),
                source.currency(), AssetKind.ORDER_COUPON, selection.instanceNo(), null, null, 1, quote.discountMinor(),
                source.items(), authorization.authorizationRef(), null, 0, null, quote.quoteRef());
        var original = new EntitlementContext(ctx.tenantNo(), ctx.actorNo(), operation,
                EntitlementServiceSnapshotPolicy.hash(json.writeValueAsString(command)));
        return new Prepared(authorization, uses.stage(original, command));
    }
    private SourceRef readSource(OrdEntAuthorization row) {
        return json.readValue(row.getSourceSnapshot(), SourceRef.class);
    }
    private OrdEntAuthorization row(String tenant, String user, String order, String quote, boolean lock) {
        var query = Wrappers.<OrdEntAuthorization>lambdaQuery().eq(OrdEntAuthorization::getTenantNo, tenant)
                .eq(OrdEntAuthorization::getUserNo, user).eq(OrdEntAuthorization::getOrderNo, order)
                .eq(OrdEntAuthorization::getQuoteRef, quote);
        if (lock) query.last("FOR UPDATE");
        return intents.selectOne(query);
    }
    private static void conflict() { throw BizException.of(ErrorCode.CONFLICT); }
}

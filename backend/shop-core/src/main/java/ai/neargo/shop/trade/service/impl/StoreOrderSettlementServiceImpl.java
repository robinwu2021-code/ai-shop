package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.spi.invoice.InvoicePort;
import ai.neargo.shop.spi.pay.PaidCollectionPort;
import ai.neargo.shop.spi.pay.PaidCollectionPort.PaidCollection;
import ai.neargo.shop.spi.trade.OrderEvents;
import ai.neargo.shop.trade.entity.OrdOfflineRecord;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdStoreCollection;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.OfflineRecordMappers.RecordMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.StoreCollectionMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import ai.neargo.shop.trade.service.StoreOrderSettlementService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link StoreOrderSettlementService} 的实现。
 *
 * <p><b>并发</b>：每条路径先锁子单行（{@code FOR UPDATE}）再加总，读已提交隔离 —— 现金与收款码同时到时，
 * 后拿到锁的一方一定看得见先提交的那笔；已不是 WAIT_PAY 的单不再转第二次，事件也就只发一次。
 *
 * <p><b>付清后落 COMPLETED</b>（不是 WAIT_FULFILL）：柜台零售当面交货，没有备货 / 发货这一步；
 * 发票来源与准备度把 WAIT_FULFILL / FULFILLING / COMPLETED 都当已付。不调结算单生成（{@code generateForOrder}）：
 * 现金与门店收款码的钱不经平台分账，资金巡检 I1 也只扫 LEGACY_FUNDED。
 *
 * <p><b>事件</b>：{@code ORDER_PAID}（userNo 为空 —— 散客）与每张子单的 {@code SUB_ORDER_PAID}，载荷与
 * {@code OrderServiceImpl#markPaidSource} 同形，自动开票（{@code InvoiceAutoIssueConsumer}）、通知、报表照常消费。
 */
@Service
public class StoreOrderSettlementServiceImpl implements StoreOrderSettlementService {

    private static final Logger log = LoggerFactory.getLogger(StoreOrderSettlementServiceImpl.class);
    static final String CHANNEL_OFFLINE = "OFFLINE";
    static final String CHANNEL_MIXED = "MIXED";
    static final String CHANNEL_COLLECT = "COLLECT";

    @org.springframework.beans.factory.annotation.Autowired
    private ai.neargo.shop.spi.member.PosSaleMemberPort posMembers;
    private final OrderMapper orders;
    private final SubOrderMapper subOrders;
    private final RecordMapper offline;
    private final StoreCollectionMapper collections;
    private final OutboxEventBus events;
    /** 内嵌形态由支付域装配；独立形态没有（收款事实走已验签推送） */
    private final ObjectProvider<PaidCollectionPort> paidCollections;
    private final ObjectProvider<InvoicePort> invoices;
    private final TransactionTemplate writes;

    public StoreOrderSettlementServiceImpl(OrderMapper orders, SubOrderMapper subOrders, RecordMapper offline,
            StoreCollectionMapper collections, OutboxEventBus events, ObjectProvider<PaidCollectionPort> paidCollections,
            ObjectProvider<InvoicePort> invoices, @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.orders = orders;
        this.subOrders = subOrders;
        this.offline = offline;
        this.collections = collections;
        this.events = events;
        this.paidCollections = paidCollections;
        this.invoices = invoices;
        this.writes = new TransactionTemplate(manager);
        this.writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    // ------------------------------------------------------------------ 现金记录

    @Override
    public void afterOfflineRecorded(String entityNo, String storeNo, String orderNo, String currency) {
        var sub = lockStoreSale(entityNo, storeNo, orderNo);
        if (sub == null) return;
        var order = orderOf(sub);
        if (!OrdSubOrder.WAIT_PAY.equals(sub.getStatus())) throw BizException.of(ErrorCode.STORE_ORDER_STATE_INVALID);
        if (!order.getCurrency().equals(currency)) throw BizException.of(ErrorCode.STORE_ORDER_CURRENCY_MISMATCH);
        settle(sub, order);
    }

    @Override
    public void requireMutable(String entityNo, String storeNo, String orderNo) {
        var sub = lockStoreSale(entityNo, storeNo, orderNo);
        if (sub != null && OrdSubOrder.PAID.contains(sub.getStatus()))
            throw BizException.of(ErrorCode.STORE_ORDER_PAYMENT_LOCKED);
    }

    @Override
    public void afterOfflineChanged(String entityNo, String storeNo, String orderNo) {
        var sub = lockStoreSale(entityNo, storeNo, orderNo);
        if (sub != null) settle(sub, orderOf(sub));
    }

    @Override
    public void refundCash(OrdOfflineRecord record, String actorNo) {
        var sub = lockStoreSale(record.getEntityNo(), record.getStoreNo(), record.getOrderNo());
        if (sub == null || !OrdSubOrder.COMPLETED.equals(sub.getStatus()) || sub.getRefundedMinor() != 0) {
            throw BizException.of(ErrorCode.STORE_ORDER_STATE_INVALID);
        }
        var order = orderOf(sub);
        long amount = record.getAmountMinor();
        if (order == null || !OrdOrder.PAID.equals(order.getStatus())
                || !record.getCurrency().equals(order.getCurrency()) || amount != order.getPayAmount()
                || paidMinor(record.getEntityNo(), record.getStoreNo(), sub.getSubOrderNo(), record.getCurrency()) != amount
                || !unscoped(() -> collectionsOf(sub.getSubOrderNo())).isEmpty()) {
            throw BizException.of(ErrorCode.STORE_ORDER_STATE_INVALID);
        }
        var invoice = invoices.getIfAvailable();
        var scope = new InvoicePort.Scope("MAIN", record.getEntityNo(), record.getStoreNo(), actorNo);
        if (invoice == null || invoice.activeOf(scope, "SUB_ORDER", sub.getSubOrderNo()).isPresent()
                || invoice.activeOf(scope, "OFFLINE_RECORD", record.getRecordNo()).isPresent()) {
            throw BizException.of(ErrorCode.INVOICE_DATA_LOCKED);
        }
        int changed = unscoped(() -> subOrders.update(null, new UpdateWrapper<OrdSubOrder>().eq("id", sub.getId())
                .set("status", OrdSubOrder.REFUNDED).set("refunded_minor", amount).set("refund_progress", "FULL")
                .setSql("version=version+1")));
        if (changed != 1) throw BizException.of(ErrorCode.CONFLICT);
    }

    // ------------------------------------------------------------------ 收款码 / 付款链接

    @Override
    public boolean attachCollection(PaidCollection c) {
        if (c.bizNo() == null || c.storeNo() == null) return false;
        var target = unscoped(() -> subOrders.selectOne(new QueryWrapper<OrdSubOrder>().eq("tenant_no", "MAIN")
                .eq("sub_order_no", c.bizNo()).eq("store_no", c.storeNo())
                .eq("admission_kind", StoreOrderServiceImpl.STORE_SALE)));
        if (target == null) return false;
        Boolean attached = unscoped(() -> writes.execute(tx -> {
            var sub = lockStoreSale(target.getEntityNo(), target.getStoreNo(), target.getSubOrderNo());
            var order = orderOf(sub);
            if (!order.getCurrency().equals(c.currency())) {
                log.warn("[store-order] 收款 {} 币别 {} 与订单 {} 的 {} 不同，不挂单", c.payOrderNo(), c.currency(),
                        sub.getSubOrderNo(), order.getCurrency());
                return false;
            }
            if (collections.selectCount(new QueryWrapper<OrdStoreCollection>().eq("tenant_no", "MAIN")
                    .eq("pay_order_no", c.payOrderNo())) > 0) return false;
            var row = new OrdStoreCollection();
            row.setTenantNo("MAIN");
            row.setPayOrderNo(c.payOrderNo());
            row.setOrderNo(sub.getOrderNo());
            row.setSubOrderNo(sub.getSubOrderNo());
            row.setEntityNo(sub.getEntityNo());
            row.setStoreNo(sub.getStoreNo());
            row.setCurrency(c.currency());
            row.setPaidMinor(c.paidMinor());
            row.setPayChannel(c.payChannel());
            collections.insert(row);
            if (OrdSubOrder.WAIT_PAY.equals(sub.getStatus())) settle(sub, order);
            // 已付清 / 已取消后才到的钱照记（多收、或取消后顾客仍付了）：退还走退款，P3
            else log.warn("[store-order] 收款 {} 到达时订单 {} 已是 {}，只记不转状态", c.payOrderNo(),
                    sub.getSubOrderNo(), sub.getStatus());
            return true;
        }));
        return Boolean.TRUE.equals(attached);
    }

    @Override
    public void onPayOrderSucceeded(String payOrderNo) {
        var port = paidCollections.getIfAvailable();
        if (port == null) return;
        port.paidCollection(payOrderNo).ifPresent(this::attachCollection);
    }

    // ------------------------------------------------------------------ 读

    @Override
    public long paidMinor(String entityNo, String storeNo, String subOrderNo, String currency) {
        return unscoped(() -> Math.addExact(offline.sumRecordedOfOrder(entityNo, storeNo, subOrderNo, currency),
                collectionsOf(subOrderNo).stream().filter(r -> currency.equals(r.getCurrency()))
                        .mapToLong(OrdStoreCollection::getPaidMinor).sum()));
    }

    // ------------------------------------------------------------------ 付清

    /** 子单已锁。还在等付款且同币别加总 ≥ 总额 → 转已付并发事件；否则什么都不做。 */
    private void settle(OrdSubOrder sub, OrdOrder order) {
        if (!OrdSubOrder.WAIT_PAY.equals(sub.getStatus())) return;
        String currency = order.getCurrency();
        long cash = unscoped(() -> offline.sumRecordedOfOrder(sub.getEntityNo(), sub.getStoreNo(),
                sub.getSubOrderNo(), currency));
        var collected = unscoped(() -> collectionsOf(sub.getSubOrderNo())).stream()
                .filter(r -> currency.equals(r.getCurrency())).toList();
        long paid = Math.addExact(cash, collected.stream().mapToLong(OrdStoreCollection::getPaidMinor).sum());
        if (paid < order.getPayAmount()) return;
        String channel = channel(cash > 0, collected);
        long now = System.currentTimeMillis();
        int changed = unscoped(() -> orders.update(null, new UpdateWrapper<OrdOrder>().eq("tenant_no", "MAIN")
                .eq("order_no", order.getOrderNo()).eq("status", OrdOrder.WAIT_PAY)
                .set(order.getPosActivityFactsVersion() != null, "pos_member_at_payment",
                        order.getPosMemberNo() != null && posMembers.active("MAIN",sub.getEntityNo(),sub.getStoreNo(),order.getPosMemberNo()))
                .set("status", OrdOrder.PAID).set("paid_at", now).set("pay_channel", channel)
                .setSql("version=version+1")));
        if (changed != 1) throw BizException.of(ErrorCode.STORE_ORDER_STATE_INVALID);
        unscoped(() -> subOrders.update(null, new UpdateWrapper<OrdSubOrder>().eq("id", sub.getId())
                .eq("status", OrdSubOrder.WAIT_PAY).set("status", OrdSubOrder.COMPLETED).set("completed_at", now)
                .setSql("version=version+1")));
        events.publish(new OrderEvents.OrderPaid(order.getOrderNo(), null, order.getPayAmount(), channel));
        events.publish(new OrderEvents.SubOrderPaid(sub.getSubOrderNo(), order.getOrderNo(), sub.getEntityNo(),
                sub.getStoreNo(), null, sub.getPayAmount() == null ? 0L : sub.getPayAmount()));
        log.info("[store-order] {} 付清：收 {} / 应收 {} {}（{}）", sub.getSubOrderNo(), paid, order.getPayAmount(),
                currency, channel);
    }

    /** 只有现金 → OFFLINE；只有一种收款通道 → 该通道；混合 → MIXED。列宽 16。 */
    private static String channel(boolean cash, List<OrdStoreCollection> collected) {
        var channels = collected.stream().map(r -> r.getPayChannel() == null ? CHANNEL_COLLECT : r.getPayChannel())
                .distinct().toList();
        if (channels.isEmpty()) return CHANNEL_OFFLINE;
        if (cash || channels.size() > 1) return CHANNEL_MIXED;
        String only = channels.getFirst();
        return only.length() > 16 ? CHANNEL_COLLECT : only;
    }

    // ------------------------------------------------------------------ helpers

    /** 本店的门店零售单子单（锁行）；不是门店零售单返回 null。须在事务内调用。 */
    private OrdSubOrder lockStoreSale(String entityNo, String storeNo, String subOrderNo) {
        if (subOrderNo == null) return null;
        return unscoped(() -> subOrders.selectOne(new QueryWrapper<OrdSubOrder>().eq("tenant_no", "MAIN")
                .eq("sub_order_no", subOrderNo).eq("entity_no", entityNo).eq("store_no", storeNo)
                .eq("admission_kind", StoreOrderServiceImpl.STORE_SALE).last("LIMIT 1 FOR UPDATE")));
    }

    private OrdOrder orderOf(OrdSubOrder sub) {
        return unscoped(() -> orders.selectOne(new QueryWrapper<OrdOrder>().eq("tenant_no", "MAIN")
                .eq("order_no", sub.getOrderNo())));
    }

    private List<OrdStoreCollection> collectionsOf(String subOrderNo) {
        return collections.selectList(new QueryWrapper<OrdStoreCollection>().eq("tenant_no", "MAIN")
                .eq("sub_order_no", subOrderNo));
    }

    private static <T> T unscoped(Supplier<T> action) {
        return DataScopeContext.executeWithoutScope(action::get);
    }
}

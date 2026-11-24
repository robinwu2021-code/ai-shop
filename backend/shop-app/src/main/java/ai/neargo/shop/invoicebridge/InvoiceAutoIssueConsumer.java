package ai.neargo.shop.invoicebridge;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.spi.invoice.InvoicePort;
import ai.neargo.shop.spi.trade.OrderEvents;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 付款成功 → 自动开发票（ADR-063）。零售、餐饮、美业的付款都会发 {@code ORDER_PAID}，所以三个行业走同一条。
 *
 * <p>只有主体打开了「自动开立」才开（{@link InvoicePort#issueIfAutomatic}），买受人用付款前预存的那一份。
 * 每张子单各自一个事务：一张开不了不连累同单的其他店。
 *
 * <p><b>门店零售单（{@code STORE_SALE}）按开票时机开</b>（TDD-统一订单与开票资料 AC8）：本单 {@code invoice_timing}
 * 优先，没有就取门店开票设定；{@code ON_PAID} 才开（买受人用预存的，没有按一般消费者），资料不齐（26002）跳过留给补资料后手动开；
 * {@code MANUAL} / {@code LATER} 不开。它不看主体的「自动开立」—— 门店设定是更细的那一层。其他订单照旧。
 *
 * <p><b>捕获的业务动作</b>：来源不可开（金额组成不支持）、没开通、预存买受人已不合规 —— 这几种重试也不会变，
 * 记日志后跳过，由收银员在 B 端手动开；其余失败（号段用尽、库不可用）原样抛出，交给 outbox 既有的重试。
 */
@Component
public class InvoiceAutoIssueConsumer implements OutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(InvoiceAutoIssueConsumer.class);
    private static final Set<ErrorCode> SKIP = Set.of(ErrorCode.INVOICE_SOURCE_NOT_READY,
            ErrorCode.INVOICE_REGIME_NOT_ENABLED, ErrorCode.INVOICE_BUYER_INVALID);
    private static final String STORE_SALE = "STORE_SALE";
    private static final Set<String> PAID =
            Set.of(OrdSubOrder.WAIT_FULFILL, OrdSubOrder.FULFILLING, OrdSubOrder.COMPLETED);

    /** 打包排除发票模块（{@code -Dinvoice.exclude}）时没有实现：本消费者不认领任何事件，见 TDD-发票模块 §17 */
    private final InvoicePort invoices;
    private final SubOrderMapper subOrders;
    private final OrderMapper orders;
    private final ObjectMapper json;

    @Autowired
    public InvoiceAutoIssueConsumer(ObjectProvider<InvoicePort> invoices, SubOrderMapper subOrders, OrderMapper orders,
            ObjectMapper json) {
        this(invoices.getIfAvailable(), subOrders, orders, json);
    }

    public InvoiceAutoIssueConsumer(InvoicePort invoices, SubOrderMapper subOrders, OrderMapper orders,
            ObjectMapper json) {
        this.invoices = invoices;
        this.subOrders = subOrders;
        this.orders = orders;
        this.json = json;
    }

    @Override
    public String handlerId() {
        return "invoice-auto-issue";
    }

    @Override
    public boolean supports(String eventType) {
        return invoices != null && "ORDER_PAID".equals(eventType);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void consume(SysOutbox event) {
        String orderNo = json.readValue(event.getPayload(), OrderEvents.OrderPaid.class).orderNo();
        if (!orderNo.equals(event.getAggregateId())) throw new IllegalArgumentException("Order identity mismatch");
        for (var sub : subOrders.selectList(new QueryWrapper<OrdSubOrder>().eq("order_no", orderNo))) {
            if (!PAID.contains(sub.getStatus()) || sub.getEntityNo() == null || sub.getStoreNo() == null) continue;
            var scope = new InvoicePort.Scope(sub.getTenantNo(), sub.getEntityNo(), sub.getStoreNo(), "SYSTEM");
            try {
                if (STORE_SALE.equals(sub.getAdmissionKind())) issueStoreSale(scope, sub);
                else invoices.issueIfAutomatic(scope, "SUB_ORDER", sub.getSubOrderNo())
                        .ifPresent(r -> invoices.uploadNow(scope, r.invoiceNo()));
            } catch (BizException e) {
                if (!SKIP.contains(e.errorCode())) throw e;
                log.warn("[invoice-auto-issue] {} 不自动开票，留给收银员手动开：{}", sub.getSubOrderNo(), e.errorCode());
            }
        }
    }

    /** 门店零售单：本单开票时机优先，其次门店设定；ON_PAID 才用预存买受人（没有按一般消费者）开。 */
    private void issueStoreSale(InvoicePort.Scope scope, OrdSubOrder sub) {
        var order = orders.selectOne(new QueryWrapper<OrdOrder>().eq("tenant_no", sub.getTenantNo())
                .eq("order_no", sub.getOrderNo()));
        String timing = order == null || order.getInvoiceTiming() == null
                ? invoices.storeSettings(scope).invoiceTiming() : order.getInvoiceTiming();
        if (!InvoicePort.StoreSettings.ON_PAID.equals(timing)) return;
        var buyer = invoices.rememberedBuyer(scope, "SUB_ORDER", sub.getSubOrderNo())
                .orElse(InvoicePort.Buyer.consumer());
        var receipt = invoices.issue(scope, "SUB_ORDER", sub.getSubOrderNo(), buyer);
        invoices.uploadNow(scope, receipt.invoiceNo());
    }
}

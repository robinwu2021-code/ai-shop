package ai.neargo.shop.invoicebridge.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import ai.neargo.shop.spi.invoice.InvoiceSourcePort;
import ai.neargo.shop.trade.entity.OrdIndustryOrderSource;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.IndustryOrderSourceMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 已付核心子单 → 发票来源（来源类型 {@code SUB_ORDER}）。零售、餐饮、美业共用这一个实现：
 * 三个行业结账最终都落成 {@code ord_sub_order} + {@code ord_item}，行业码取自 {@code ord_industry_order_source}。
 *
 * <p><b>只接「商品明细 + 运费 − 优惠 = 实付」的子单</b>。积分抵扣、称重补差、开票前已部分退款等组成，
 * 首期不推断它们在发票上该怎么表达，直接回 {@code INVOICE_SOURCE_NOT_READY}，而不是开一张与收款对不上的票
 * （TDD-发票模块 §4）。
 *
 * <p>主体与门店已由发票域按登录上下文收窄后传入，这里显式按它们查，所以绕开数据域拦截器（同一事实不判两次）。
 */
@Component
public class SubOrderInvoiceSourcePortImpl implements InvoiceSourcePort {

    static final String SOURCE_TYPE = "SUB_ORDER";
    private static final Set<String> PAID =
            Set.of(OrdSubOrder.WAIT_FULFILL, OrdSubOrder.FULFILLING, OrdSubOrder.COMPLETED);

    private final SubOrderMapper subOrders;
    private final OrderMapper orders;
    private final OrderItemMapper items;
    private final IndustryOrderSourceMapper industrySources;

    public SubOrderInvoiceSourcePortImpl(SubOrderMapper subOrders, OrderMapper orders, OrderItemMapper items,
            IndustryOrderSourceMapper industrySources) {
        this.subOrders = subOrders;
        this.orders = orders;
        this.items = items;
        this.industrySources = industrySources;
    }

    @Override
    public Set<String> sourceTypes() {
        return Set.of(SOURCE_TYPE);
    }

    @Override
    public InvoiceSource load(Scope scope, String sourceType, String subOrderNo) {
        return DataScopeContext.executeWithoutScope(() -> read(scope, subOrderNo));
    }

    private InvoiceSource read(Scope scope, String subOrderNo) {
        var sub = subOrders.selectOne(new QueryWrapper<OrdSubOrder>().eq("tenant_no", scope.tenantNo())
                .eq("entity_no", scope.entityNo()).eq("store_no", scope.storeNo()).eq("sub_order_no", subOrderNo));
        if (sub == null || !PAID.contains(sub.getStatus()) || positive(sub.getRefundedMinor())
                || positive(sub.getPointsDeductMinor()) || nonZero(sub.getWeighAdjustMinor()))
            throw notReady();
        var order = orders.selectOne(new QueryWrapper<OrdOrder>().eq("tenant_no", scope.tenantNo())
                .eq("order_no", sub.getOrderNo()));
        if (order == null || order.getPaidAt() == null) throw notReady();
        var lines = new ArrayList<Line>();
        for (var item : items.selectList(new QueryWrapper<OrdItem>().eq("sub_order_no", subOrderNo)
                .orderByAsc("id"))) {
            BigDecimal quantity = item.getSaleQuantity() != null ? item.getSaleQuantity()
                    : BigDecimal.valueOf(item.getQty());
            lines.add(new Line(String.valueOf(item.getId()), item.getTitle(), quantity, item.getQuantityUnit(),
                    item.getPrice(), item.getAmount()));
        }
        long freight = value(sub.getFreightAmount()), discount = value(sub.getDiscountAmount());
        if (freight > 0) lines.add(new Line("FREIGHT", "運費", BigDecimal.ONE, null, freight, freight));
        if (discount > 0) lines.add(new Line("DISCOUNT", "折扣", BigDecimal.ONE, null, -discount, -discount));
        return new InvoiceSource(industry(scope, subOrderNo), order.getCurrency(),
                Instant.ofEpochMilli(order.getPaidAt()), value(sub.getPayAmount()), lines);
    }

    /** 行业单的来源系统即行业（FOOD / BEAUTY …）；没有行业来源绑定的是普通零售下单。 */
    private String industry(Scope scope, String subOrderNo) {
        var binding = industrySources.selectOne(new QueryWrapper<OrdIndustryOrderSource>()
                .eq("tenant_no", scope.tenantNo()).eq("sub_order_no", subOrderNo).last("LIMIT 1"));
        return binding == null || "CORE".equals(binding.getSourceSystem()) ? "RETAIL" : binding.getSourceSystem();
    }

    private static long value(Long v) {
        return v == null ? 0 : v;
    }

    private static boolean positive(Long v) {
        return v != null && v > 0;
    }

    private static boolean nonZero(Long v) {
        return v != null && v != 0;
    }

    private static BizException notReady() {
        return BizException.of(ErrorCode.INVOICE_SOURCE_NOT_READY);
    }
}

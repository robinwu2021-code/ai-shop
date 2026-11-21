package ai.neargo.shop.invoicebridge.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import ai.neargo.shop.spi.invoice.InvoiceSourcePort;
import ai.neargo.shop.trade.entity.OrdIndustryOrderSource;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdPriceQuoteLine;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.IndustryOrderSourceMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.PriceQuoteLineMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 已付核心子单 → 发票来源（来源类型 {@code SUB_ORDER}）。零售、餐饮、美业共用这一个实现：
 * 三个行业结账最终都落成 {@code ord_sub_order} + {@code ord_item}，行业码取自 {@code ord_industry_order_source}。
 *
 * <p><b>金额组成</b>：商品明细 + 运费 − 优惠 − 积分折抵 ± 称重调整 = 实付，每一项一行（折扣类为负数）。
 * 对不上、或开票前已部分退款（那应开折让）时回 {@code INVOICE_SOURCE_NOT_READY}，不开一张与收款对不上的票。
 *
 * <p><b>课税别</b>取自行业单冻结的报价行税类（{@code allocations_json} 把订单行连到报价源行）：
 * STANDARD → 应税、ZERO_RATED → 零税率、EXEMPT → 免税；没有行业绑定的普通零售单按应税。
 * 混合税别时整单级的优惠 / 积分折抵 / 称重调整分不到各税别，这种单回 NOT_READY（TDD-发票模块 §4）。
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
    private final PriceQuoteLineMapper quoteLines;
    private final ObjectMapper json;

    public SubOrderInvoiceSourcePortImpl(SubOrderMapper subOrders, OrderMapper orders, OrderItemMapper items,
            IndustryOrderSourceMapper industrySources, PriceQuoteLineMapper quoteLines, ObjectMapper json) {
        this.subOrders = subOrders;
        this.orders = orders;
        this.items = items;
        this.industrySources = industrySources;
        this.quoteLines = quoteLines;
        this.json = json;
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
        if (sub == null || !PAID.contains(sub.getStatus()) || value(sub.getRefundedMinor()) > 0) throw notReady();
        var order = orders.selectOne(new QueryWrapper<OrdOrder>().eq("tenant_no", scope.tenantNo())
                .eq("order_no", sub.getOrderNo()));
        if (order == null || order.getPaidAt() == null) throw notReady();
        var binding = industrySources.selectOne(new QueryWrapper<OrdIndustryOrderSource>()
                .eq("tenant_no", scope.tenantNo()).eq("sub_order_no", subOrderNo).last("LIMIT 1"));
        var taxTypes = taxTypesByItem(binding);
        var lines = new ArrayList<Line>();
        var types = new HashSet<String>();
        for (var item : items.selectList(new QueryWrapper<OrdItem>().eq("sub_order_no", subOrderNo)
                .orderByAsc("id"))) {
            BigDecimal quantity = item.getSaleQuantity() != null ? item.getSaleQuantity()
                    : BigDecimal.valueOf(item.getQty());
            String type = taxTypes.getOrDefault(item.getId(), Line.TAXABLE);
            types.add(type);
            lines.add(new Line(String.valueOf(item.getId()), item.getTitle(), quantity, item.getQuantityUnit(),
                    item.getPrice(), item.getAmount(), type));
        }
        long freight = value(sub.getFreightAmount()), discount = value(sub.getDiscountAmount()),
                points = value(sub.getPointsDeductMinor()), weigh = value(sub.getWeighAdjustMinor());
        if (types.size() > 1 && (discount != 0 || points != 0 || weigh != 0)) throw notReady();
        String whole = types.size() == 1 ? types.iterator().next() : Line.TAXABLE;
        if (freight > 0) lines.add(new Line("FREIGHT", "運費", BigDecimal.ONE, null, freight, freight, Line.TAXABLE));
        if (discount > 0) lines.add(new Line("DISCOUNT", "折扣", BigDecimal.ONE, null, -discount, -discount, whole));
        if (points > 0) lines.add(new Line("POINTS", "點數折抵", BigDecimal.ONE, null, -points, -points, whole));
        if (weigh != 0) lines.add(new Line("WEIGH", "秤重調整", BigDecimal.ONE, null, weigh, weigh, whole));
        String industry = binding == null || "CORE".equals(binding.getSourceSystem()) ? "RETAIL"
                : binding.getSourceSystem();
        return new InvoiceSource(industry, order.getCurrency(), Instant.ofEpochMilli(order.getPaidAt()),
                value(sub.getPayAmount()), lines);
    }

    /** 订单行 id → 课税别：经行业绑定里的分摊（订单行 ↔ 报价源行）取报价行的冻结税类。 */
    private Map<Long, String> taxTypesByItem(OrdIndustryOrderSource binding) {
        var out = new HashMap<Long, String>();
        if (binding == null || binding.getAllocationsJson() == null || binding.getQuoteNo() == null) return out;
        var classes = new HashMap<String, String>();
        for (var q : quoteLines.selectList(new QueryWrapper<OrdPriceQuoteLine>()
                .eq("quote_no", binding.getQuoteNo()))) {
            String type = switch (q.getTaxClass()) {
                case "STANDARD" -> Line.TAXABLE;
                case "ZERO_RATED" -> Line.ZERO;
                case "EXEMPT" -> Line.EXEMPT;
                default -> throw notReady(); // 不在课税范围 / 税类未知：不能出现在发票上
            };
            var previous = classes.putIfAbsent(q.getSourceLineNo(), type);
            if (previous != null && !previous.equals(type)) throw notReady();
        }
        for (var a : json.readTree(binding.getAllocationsJson()).path("allocations")) {
            String type = classes.get(a.path("sourceLineNo").asText());
            if (type == null) throw notReady();
            out.put(a.path("orderItemId").asLong(), type);
        }
        return out;
    }

    private static long value(Long v) {
        return v == null ? 0 : v;
    }

    private static BizException notReady() {
        return BizException.of(ErrorCode.INVOICE_SOURCE_NOT_READY);
    }
}

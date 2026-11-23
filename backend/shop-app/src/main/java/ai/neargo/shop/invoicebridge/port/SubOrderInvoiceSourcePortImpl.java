package ai.neargo.shop.invoicebridge.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizDetailedException;
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
import ai.neargo.shop.trade.order.refund.policy.LineShares;
import ai.neargo.shop.trade.policy.InvoiceReadinessPolicy;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
 * <p><b>品名与课税别</b>：订单行带开票资料（门店零售单，V692 {@code invoice_name / tax_class}）时以它为准，
 * 缺任何一项回 {@code INVOICE_SOURCE_NOT_READY} 且 {@code data.missing} 列出缺哪几行哪几项（与订单准备度同一口径，
 * {@link InvoiceReadinessPolicy}）。没有这两列的旧订单保持原规则：取行业单冻结的报价行税类
 * （{@code allocations_json} 把订单行连到报价源行），没有行业绑定的普通零售单按应税。
 * STANDARD → 应税、ZERO_RATED → 零税率、EXEMPT → 免税。
 *
 * <p><b>混合税别</b>时整单级的优惠 / 积分折抵 / 称重调整按各税别的明细金额比例（最大余数法，与
 * {@link LineShares} 同一算法）分到各税别，每个税别各出一行（TDD-统一订单与开票资料 AC10；原先回 NOT_READY）。
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
        var rows = items.selectList(new QueryWrapper<OrdItem>().eq("sub_order_no", subOrderNo).orderByAsc("id"));
        boolean frozenData = InvoiceReadinessPolicy.carriesInvoiceData(rows);
        if (frozenData) {
            var missing = InvoiceReadinessPolicy.missing(rows);
            if (!missing.isEmpty()) throw new BizDetailedException(ErrorCode.INVOICE_SOURCE_NOT_READY,
                    Map.of("missing", missing.stream().map(InvoiceReadinessPolicy.Missing::asMap).toList()));
        }
        var taxTypes = frozenData ? Map.<Long, String>of() : taxTypesByItem(binding);
        var lines = new ArrayList<Line>();
        var weightByType = new LinkedHashMap<String, Long>();
        for (var item : rows) {
            BigDecimal quantity = item.getSaleQuantity() != null ? item.getSaleQuantity()
                    : BigDecimal.valueOf(item.getQty());
            String type = frozenData ? lineType(item.getTaxClass()) : taxTypes.getOrDefault(item.getId(), Line.TAXABLE);
            String name = frozenData ? item.getInvoiceName() : item.getTitle();
            weightByType.merge(type, Math.max(0L, item.getAmount()), Math::addExact);
            lines.add(new Line(String.valueOf(item.getId()), name, quantity, item.getQuantityUnit(),
                    item.getPrice(), item.getAmount(), type));
        }
        long freight = value(sub.getFreightAmount()), discount = value(sub.getDiscountAmount()),
                points = value(sub.getPointsDeductMinor()), weigh = value(sub.getWeighAdjustMinor());
        if (freight > 0) lines.add(new Line("FREIGHT", "運費", BigDecimal.ONE, null, freight, freight, Line.TAXABLE));
        if (discount > 0) addSplit(lines, weightByType, "DISCOUNT", "折扣", -discount);
        if (points > 0) addSplit(lines, weightByType, "POINTS", "點數折抵", -points);
        if (weigh != 0) addSplit(lines, weightByType, "WEIGH", "秤重調整", weigh);
        String industry = binding == null || "CORE".equals(binding.getSourceSystem()) ? "RETAIL"
                : binding.getSourceSystem();
        return new InvoiceSource(industry, order.getCurrency(), Instant.ofEpochMilli(order.getPaidAt()),
                value(sub.getPayAmount()), lines, blankToNull(sub.getUserNo()));
    }

    @Override
    public List<BuyerSource> sourcesOfBuyerOrder(String tenantNo, String buyerUserNo, String orderNo) {
        return DataScopeContext.executeWithoutScope(() -> subOrders.selectList(new QueryWrapper<OrdSubOrder>()
                        .eq("tenant_no", tenantNo).eq("order_no", orderNo).eq("user_no", buyerUserNo)
                        .notIn("status", OrdSubOrder.CANCELLED, OrdSubOrder.REFUNDED).orderByAsc("id"))
                .stream().filter(s -> s.getEntityNo() != null && s.getStoreNo() != null)
                .map(s -> new BuyerSource(SOURCE_TYPE, s.getSubOrderNo(), s.getEntityNo(), s.getStoreNo(),
                        PAID.contains(s.getStatus())))
                .toList());
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

    /**
     * 整单级金额（折扣 / 积分为负，称重调整可正可负）按各税别明细金额比例分到各税别，最大余数法：
     * Σ 恰等于原额。只有一个税别时就是一行，与原先相同；分到 0 的税别不出行。
     */
    private static void addSplit(List<Line> lines, Map<String, Long> weightByType, String ref, String name,
            long amount) {
        List<String> types = weightByType.isEmpty() ? List.of(Line.TAXABLE) : new ArrayList<>(weightByType.keySet());
        if (types.size() == 1) {
            lines.add(new Line(ref, name, BigDecimal.ONE, null, amount, amount, types.getFirst()));
            return;
        }
        long[] weights = types.stream().mapToLong(weightByType::get).toArray();
        long[] shares = LineShares.allocate(Math.abs(amount), weights);
        if (Arrays.stream(shares).sum() != Math.abs(amount)) throw notReady(); // 明细全为 0：分不了
        for (int i = 0; i < types.size(); i++) {
            if (shares[i] == 0) continue;
            long part = amount < 0 ? -shares[i] : shares[i];
            lines.add(new Line(ref + ":" + types.get(i), name, BigDecimal.ONE, null, part, part, types.get(i)));
        }
    }

    /** 订单行冻结的课税别 → 发票明细税别；准备度已保证三者之一 */
    private static String lineType(String taxClass) {
        return switch (taxClass) {
            case "STANDARD" -> Line.TAXABLE;
            case "ZERO_RATED" -> Line.ZERO;
            case "EXEMPT" -> Line.EXEMPT;
            default -> throw notReady();
        };
    }

    private static long value(Long v) {
        return v == null ? 0 : v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static BizException notReady() {
        return BizException.of(ErrorCode.INVOICE_SOURCE_NOT_READY);
    }
}

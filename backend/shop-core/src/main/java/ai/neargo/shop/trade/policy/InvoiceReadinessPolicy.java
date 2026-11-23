package ai.neargo.shop.trade.policy;

import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 订单行开票资料「缺什么」与准备度的唯一口径（TDD-统一订单与开票资料 AC4 / AC9，ADR-065 决策 3）。
 *
 * <p><b>为什么放在一处</b>：订单详情要显示「待补资料」，发票来源要在开票时拒绝并回同一份 missing。
 * 两边各算一遍，迟早出现「详情说可开、开票说缺品名」—— 所以门店零售单的读视图与
 * {@code SubOrderInvoiceSourcePortImpl} 都调这里。
 *
 * <p>只认带了 {@code line_kind} 的订单行（V692 之后的门店零售单）；四列全空的旧订单行不由这里判，
 * 发票来源对它们保持原规则（行业绑定取报价税类，无绑定按应税）。
 *
 * <p>行号 {@code lineNo} 是子单内按行 id 升序的序号（从 1 起）：订单行建单后不增不删，序号稳定。
 */
public final class InvoiceReadinessPolicy {

    public static final String NOT_PAID = "NOT_PAID";
    public static final String NEEDS_INFO = "NEEDS_INFO";
    public static final String READY = "READY";
    public static final String ISSUED = "ISSUED";
    public static final String REFUNDED = "REFUNDED";

    /** 对外字段名：与请求 / 响应里的 {@code name}、{@code taxType} 同名 */
    public static final String FIELD_NAME = "name";
    public static final String FIELD_TAX_TYPE = "taxType";

    /** 库内课税别（商品侧口径）与对外 taxType 的对照：对外 TAXABLE / ZERO_RATED / EXEMPT */
    private static final Map<String, String> TAX_TYPE_OF_CLASS =
            Map.of("STANDARD", "TAXABLE", "ZERO_RATED", "ZERO_RATED", "EXEMPT", "EXEMPT");
    private static final Map<String, String> CLASS_OF_TAX_TYPE =
            Map.of("TAXABLE", "STANDARD", "ZERO_RATED", "ZERO_RATED", "EXEMPT", "EXEMPT");

    private InvoiceReadinessPolicy() {
    }

    /** 一行缺的字段；fields 按 name、taxType 的固定顺序 */
    public record Missing(int lineNo, List<String> fields) {
        public Missing {
            fields = List.copyOf(fields);
        }

        public Map<String, Object> asMap() {
            return Map.of("lineNo", lineNo, "fields", fields);
        }
    }

    /** 这张子单的订单行是否带开票资料列（门店零售单）。 */
    public static boolean carriesInvoiceData(List<OrdItem> items) {
        return !items.isEmpty() && items.stream().allMatch(i -> i.getLineKind() != null);
    }

    /** @param items 同一子单的全部订单行，按 id 升序 */
    public static List<Missing> missing(List<OrdItem> items) {
        var out = new ArrayList<Missing>();
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            var fields = new ArrayList<String>(2);
            if (blank(item.getInvoiceName())) fields.add(FIELD_NAME);
            if (taxType(item.getTaxClass()) == null) fields.add(FIELD_TAX_TYPE);
            if (!fields.isEmpty()) out.add(new Missing(i + 1, fields));
        }
        return out;
    }

    /**
     * 准备度：已开票 &gt; 有退款 &gt; 缺资料 &gt; 未付清 &gt; 可开票。
     *
     * <p>缺资料排在未付清前面：结账页上最要紧的是「先补品名、课税别」，付没付清收款那边自己显示。
     */
    public static String state(OrdSubOrder sub, boolean issued, List<Missing> missing) {
        if (issued) return ISSUED;
        if (OrdSubOrder.REFUNDED.equals(sub.getStatus())
                || sub.getRefundedMinor() != null && sub.getRefundedMinor() > 0) return REFUNDED;
        if (!missing.isEmpty()) return NEEDS_INFO;
        if (!OrdSubOrder.PAID.contains(sub.getStatus())) return NOT_PAID;
        return READY;
    }

    /** 库内课税别 → 对外 taxType；不在发票范围（OUT_OF_SCOPE / 未知 / 空）返回 null，即「待补」。 */
    public static String taxType(String taxClass) {
        return taxClass == null ? null : TAX_TYPE_OF_CLASS.get(taxClass);
    }

    /** 对外 taxType → 库内课税别。入参已由请求校验限定在三种之内。 */
    public static String taxClass(String taxType) {
        return CLASS_OF_TAX_TYPE.get(taxType);
    }

    /** 零税率 / 免税会少报税：自订行选它们要另有权限（AC6）。 */
    public static boolean reducesTax(String taxType) {
        return "ZERO_RATED".equals(taxType) || "EXEMPT".equals(taxType);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}

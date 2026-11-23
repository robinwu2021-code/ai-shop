package ai.neargo.shop.trade.service;

import java.util.List;

/**
 * 门店零售单（TDD-统一订单与开票资料，ADR-065）：店员在 POS 给散客建单，订单行自带开票品名与课税别。
 *
 * <p><b>为什么另开一条建单路径</b>：C 端结账、行业单都按 SKU 走统一试算，自订行（直接输入金额）进不去；
 * 美业柜台单只收到店核销服务。这里直接落 {@code ord_order / ord_sub_order / ord_item}，一单一子单，
 * 不经促销引擎 —— POS 的改价、整单优惠由店员录入、按成交价收款。
 *
 * <p>作用域（主体、门店、操作人）与操作人持有的两项附加权限由调用方从 {@code BizContext} 取，请求体不收。
 */
public interface StoreOrderService {

    /**
     * 建单。门店内同一 {@code clientOrderNo} 只落一单，再送（含离线补传）返回原单；
     * 同一幂等键换了 {@code clientOrderNo} 回 10409。
     */
    StoreSaleView create(Scope scope, CreateCmd cmd);

    StoreSaleView get(Scope scope, String orderNo);

    /**
     * 补开票资料：只补空着的行品名、课税别与买受人；已有值的给不同值回 {@code INVOICE_DATA_FROZEN}，
     * 已开票回 {@code INVOICE_DATA_LOCKED}。每补一项写一条稽核；同一幂等键再送返回当前视图。
     */
    StoreSaleView fillInvoiceData(Scope scope, String orderNo, FillCmd cmd);

    /**
     * 取消未付款的单；已取消的原样返回，已付款或已挂了收款（部分付款）的回 {@code STORE_ORDER_STATE_INVALID}
     * —— 先作废现金记录 / 退收款，再取消。
     */
    StoreSaleView cancel(Scope scope, String orderNo, String reason);

    /**
     * @param mayReprice     操作人有 {@code biz:order:reprice}：SKU 行可按不同于商品价的成交价卖
     * @param mayOverrideTax 操作人有 {@code biz:invoice:tax-override}：可选零税率 / 免税
     */
    record Scope(String entityNo, String storeNo, String operatorNo, boolean mayReprice, boolean mayOverrideTax) {}

    /**
     * @param buyer         买受人（可空）：建单同一事务预存
     * @param invoiceTiming 本单开票时机 ON_PAID / MANUAL / LATER（P2）：存在订单上，null = 跟门店开票设定
     */
    record CreateCmd(String idempotencyKey, String clientOrderNo, String channel, String currency,
            List<LineCmd> lines, List<AdjustmentCmd> adjustments, BuyerCmd buyer, String invoiceTiming) {}

    /**
     * @param storeItemNo 门店常用品项（仅自订行，与 name / taxType 互斥）：带出品名与课税别，选零税率 / 免税不需
     *                    {@code biz:invoice:tax-override}；不存在或已停用回 {@code STORE_ORDER_STORE_ITEM_UNAVAILABLE}
     * @param taxType     自订行没填（也没选品项）时取门店默认课税别（{@code STORE_DEFAULT}），门店没设则待补
     */
    record LineCmd(String kind, String skuNo, int quantity, long unitPriceMinor, String name, String taxType,
            String storeItemNo) {}

    record AdjustmentCmd(String kind, String name, long amountMinor) {}

    /** 买受人四选一：CONSUMER / COMPANY（taxId + name）/ CARRIER（carrierType + carrierId）/ DONATE（donateCode） */
    record BuyerCmd(String type, String taxId, String name, String carrierType, String carrierId, String donateCode) {}

    record FillCmd(String idempotencyKey, List<FillLineCmd> lines, BuyerCmd buyer, String reason) {}

    /** @param storeItemNo 门店常用品项：补空着的品名与课税别（与 name / taxType 互斥），零税率 / 免税不需另授权 */
    record FillLineCmd(int lineNo, String name, String taxType, String storeItemNo) {}

    /**
     * 对外视图（POS 契约，TDD §2「关键数据结构」）。{@code status}：AWAITING_PAYMENT / PAID / CANCELLED / REFUNDED。
     * {@code paidMinor}：已挂收款合计（未作废的现金记录 + 已成功的收款码 / 付款链接，同币别），付清后可大于
     * {@code totalMinor}（多收，POS 找零）；{@code invoiceTiming}：本单开票时机，null = 跟门店设定。
     */
    record StoreSaleView(String orderNo, String subOrderNo, String clientOrderNo, String status, String currency,
            long totalMinor, long paidMinor, String invoiceTiming, List<LineView> lines,
            InvoiceReadinessView invoiceReadiness) {}

    /** @param taxType TAXABLE / ZERO_RATED / EXEMPT；null = 待补 */
    record LineView(int lineNo, String kind, String skuNo, String name, String taxType, int quantity,
            long unitPriceMinor, long amountMinor, boolean frozen) {}

    /** @param state NOT_PAID / NEEDS_INFO / READY / ISSUED / REFUNDED */
    record InvoiceReadinessView(String state, List<MissingView> missing, boolean buyerSet, String invoiceNo) {}

    record MissingView(int lineNo, List<String> fields) {}
}

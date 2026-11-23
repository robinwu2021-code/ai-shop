package ai.neargo.shop.spi.invoice;

import java.util.Optional;

/**
 * 行业结账后开发票的唯一入口（ADR-063，TDD-发票模块 §2.2）。
 *
 * <p><b>为什么只收来源单号、不收金额明细</b>：金额由发票域经 {@link InvoiceSourcePort} 向来源所有者读冻结事实。
 * 让调用方把明细递进来，等于让收银端决定发票开多少钱 —— 与「不接受调用方自报价格」同一条规矩。
 *
 * <p>同一来源单同时只有一张有效发票：重复调用返回原发票，不再取号。
 */
public interface InvoicePort {

    InvoiceReceipt issue(Scope scope, String sourceType, String sourceNo, Buyer buyer);

    Optional<InvoiceReceipt> activeOf(Scope scope, String sourceType, String sourceNo);

    /**
     * 付款前预存买受人（收银员问过载具 / 统编之后）。格式按主体开通的税制校验，不合规当场拒绝；
     * 同一来源再记一次以最后一次为准。
     */
    void rememberBuyer(Scope scope, String sourceType, String sourceNo, Buyer buyer);

    /** 付款前预存的买受人（{@link #rememberBuyer}）；没存过为空。门店零售单据此显示 {@code buyerSet}、判买受人是否已定。 */
    Optional<Buyer> rememberedBuyer(Scope scope, String sourceType, String sourceNo);

    /**
     * 付款成功后的自动开票：主体开通了税制且打开「自动开立」才开，用预存买受人（没有则一般消费者）。
     * 没开通 / 没打开时返回空，不是错误。每次调用自成一个事务。
     */
    Optional<InvoiceReceipt> issueIfAutomatic(Scope scope, String sourceType, String sourceNo);

    /**
     * 门店开票设定（TDD-统一订单与开票资料 AC11）：自订行的默认课税别与开票时机。没设过返回
     * {@code (null, MANUAL)}，不是错误。
     */
    StoreSettings storeSettings(Scope scope);

    /** 本店的一个常用品项（含已停用的，调用方按 {@code enabled} 判）；不存在或别店的为空。 */
    Optional<StoreItem> storeItem(Scope scope, String storeItemNo);

    /**
     * @param defaultTaxType 自订行没填课税别时用的默认值：TAXABLE / ZERO_RATED / EXEMPT；null = 不带默认（待补）
     * @param invoiceTiming  ON_PAID 付清且资料齐自动开 / MANUAL 手动 / LATER 稍后再补
     */
    record StoreSettings(String defaultTaxType, String invoiceTiming) {
        public static final String ON_PAID = "ON_PAID";
        public static final String MANUAL = "MANUAL";
    }

    /** @param taxType TAXABLE / ZERO_RATED / EXEMPT */
    record StoreItem(String storeItemNo, String name, String taxType, boolean enabled) {}

    /** 谁在哪家店开票。主体与门店由调用方从已鉴权的上下文取，不从请求体取。 */
    record Scope(String tenantNo, String entityNo, String storeNo, String actorNo) {}

    /**
     * 买受人选择。四种形态：一般消费者（全空）、打统编（taxId）、存载具（carrierType + carrierId）、捐赠（donateCode）。
     * 合法组合与格式由税制校验，这里不判。
     */
    record Buyer(String taxId, String name, String carrierType, String carrierId, String donateCode) {
        public static Buyer consumer() {
            return new Buyer(null, null, null, null, null);
        }
    }

    record InvoiceReceipt(
            String invoiceNo,
            String regimeCode,
            String fiscalNumber,
            String periodCode,
            String randomCode,
            String status,
            boolean printMark,
            String currency,
            long totalMinor) {}
}

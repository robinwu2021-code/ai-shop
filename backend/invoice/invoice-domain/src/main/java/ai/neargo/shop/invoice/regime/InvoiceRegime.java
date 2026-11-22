package ai.neargo.shop.invoice.regime;

import ai.neargo.shop.invoice.entity.IvcAllowance;
import ai.neargo.shop.invoice.entity.IvcInvoice;
import ai.neargo.shop.invoice.entity.IvcInvoiceLine;
import ai.neargo.shop.invoice.entity.IvcRegimeProfile;
import ai.neargo.shop.spi.invoice.InvoicePort.Buyer;
import ai.neargo.shop.spi.invoice.InvoiceSourcePort;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 税制 SPI（ADR-063）：一个国家/地区的发票规则。开立主流程（幂等、取号事务、状态、上传任务、打印）与税制无关，
 * 所以新增地区只加一个实现类，不动 {@code InvoiceServiceImpl}。
 *
 * <p>实现必须是纯规则：不读库、不调通道。需要的事实（配置、密钥、已开发票）由主流程传入。
 */
public interface InvoiceRegime {

    /** 税制编码，与 {@code ivc_regime_profile.regime_code} 对应。 */
    String code();

    /** 开立时刻所属的申报期别（号段按期别配给）。 */
    String periodOf(Instant at);

    /** 期别结束的时刻（不含）；无期别的地区返回 null。下一期 = {@code periodOf(periodEnd(p))}。 */
    Instant periodEnd(String periodCode);

    /** 买受人组合与格式；不合规抛 {@code INVOICE_BUYER_INVALID}。 */
    void validateBuyer(Buyer buyer, IvcRegimeProfile profile);

    /** 卖方税号格式；不合规抛 {@code INVOICE_BUYER_INVALID}（同一类「税号不对」）。 */
    void validateSeller(String sellerTaxId);

    /** 号段字轨与号码格式；不合规抛 {@code INVOICE_RANGE_INVALID}。 */
    void validateRange(String periodCode, String track, int startNo, int endNo);

    /** 按税制口径把明细（含税）拆成应税 / 零税率 / 免税销售额与税额。 */
    Amounts amounts(List<InvoiceSourcePort.Line> lines, Buyer buyer);

    /** 是否应印纸本凭证（台湾：存载具或捐赠则不印证明联）。 */
    boolean printMark(Buyer buyer);

    String buyerType(Buyer buyer);

    String randomCode();

    /** 字轨 + 号码拼成法定号码。 */
    String fiscalNumber(String track, int number);

    /** 证明联冻结内容；{@code qrKey} 为该主体的加密密钥（可为空，空则不生成需加密的码）。 */
    Map<String, Object> proof(IvcInvoice invoice, List<IvcInvoiceLine> lines, byte[] qrKey);

    /**
     * 交易明细的字段（逐行明细由主流程另给）：存了载具 / 捐赠时写明去向，打统编时列销售额与税额。
     * 与证明联不同，它不是法定凭证，所以 58 / 80 两种纸宽都可以。
     */
    Map<String, Object> detail(IvcInvoice invoice);

    /** 折让证明单的字段：原发票、未税 / 税额 / 合计、签收栏。 */
    Map<String, Object> allowanceProof(IvcInvoice invoice, IvcAllowance allowance);

    boolean voidable(IvcInvoice invoice, Instant now);

    /** 折让的税额（含税金额中所含税额）。 */
    long allowanceTax(IvcInvoice invoice, long amountMinor);

    /** 报文的法定上报窗口：从开立 / 作废 / 折让那一刻起算（台湾 B2C 48 小时、B2B 7 日）。 */
    Duration reportWindow(String messageType, IvcInvoice invoice);

    /** 折让能否作废（台湾：折让所属期别仍是当期）。 */
    boolean allowanceVoidable(IvcAllowance allowance, Instant now);

    /** 上传报文；messageType 为 ISSUE / VOID / ALLOWANCE / ALLOWANCE_VOID。 */
    Map<String, Object> message(
            String messageType, IvcInvoice invoice, List<IvcInvoiceLine> lines, IvcAllowance allowance);

    /**
     * @param salesMinor 应税销售额（税制口径：台湾 B2C 为含税、税额 0）
     * @param taxType TAXABLE / ZERO / EXEMPT，三类并存为 MIXED
     */
    record Amounts(long salesMinor, long zeroTaxSalesMinor, long freeTaxSalesMinor, long taxMinor, long totalMinor,
            String taxType, int taxRateBp) {}
}

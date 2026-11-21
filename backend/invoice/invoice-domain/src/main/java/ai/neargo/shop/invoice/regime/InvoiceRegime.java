package ai.neargo.shop.invoice.regime;

import ai.neargo.shop.invoice.entity.IvcAllowance;
import ai.neargo.shop.invoice.entity.IvcInvoice;
import ai.neargo.shop.invoice.entity.IvcInvoiceLine;
import ai.neargo.shop.invoice.entity.IvcRegimeProfile;
import ai.neargo.shop.spi.invoice.InvoicePort.Buyer;
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

    /** 买受人组合与格式；不合规抛 {@code INVOICE_BUYER_INVALID}。 */
    void validateBuyer(Buyer buyer, IvcRegimeProfile profile);

    /** 卖方税号格式；不合规抛 {@code INVOICE_BUYER_INVALID}（同一类「税号不对」）。 */
    void validateSeller(String sellerTaxId);

    /** 号段字轨与号码格式；不合规抛 {@code INVOICE_RANGE_INVALID}。 */
    void validateRange(String periodCode, String track, int startNo, int endNo);

    /** 按税制口径把含税总计拆成销售额与税额。 */
    Amounts amounts(long totalMinor, Buyer buyer);

    /** 是否应印纸本凭证（台湾：存载具或捐赠则不印证明联）。 */
    boolean printMark(Buyer buyer);

    String buyerType(Buyer buyer);

    String randomCode();

    /** 字轨 + 号码拼成法定号码。 */
    String fiscalNumber(String track, int number);

    /** 证明联冻结内容；{@code qrKey} 为该主体的加密密钥（可为空，空则不生成需加密的码）。 */
    Map<String, Object> proof(IvcInvoice invoice, List<IvcInvoiceLine> lines, byte[] qrKey);

    boolean voidable(IvcInvoice invoice, Instant now);

    /** 折让的税额（含税金额中所含税额）。 */
    long allowanceTax(IvcInvoice invoice, long amountMinor);

    /** 上传报文；messageType 为 ISSUE / VOID / ALLOWANCE。 */
    Map<String, Object> message(
            String messageType, IvcInvoice invoice, List<IvcInvoiceLine> lines, IvcAllowance allowance);

    record Amounts(long salesMinor, long taxMinor, long totalMinor, String taxType, int taxRateBp) {}
}

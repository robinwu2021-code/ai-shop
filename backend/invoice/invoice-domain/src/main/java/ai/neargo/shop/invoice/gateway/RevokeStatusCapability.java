package ai.neargo.shop.invoice.gateway;

import ai.neargo.shop.invoice.gateway.InvoiceGateway.ProviderAccount;
import java.time.LocalDate;

/**
 * 注销进度（TDD-发票-注销与同号重开 §5，汎宇 Z11）。注销本身是上传类报文 {@code REVOKE}（MIG F0701），走 {@code send}；
 * 服务商受理后要经它查流程是否完成。有此能力的供应商才开放注销。失败抛 {@link ProviderException}。
 */
public interface RevokeStatusCapability {

    /** @param invoiceDate 原发票开立日（税制时区） */
    RevokeState revokeStatus(ProviderAccount account, String fiscalNumber, LocalDate invoiceDate);

    /** 汎宇 Z11 STATUSCODE：1 完成 / 2 尚未完成 / 3 失败。 */
    enum RevokeState { DONE, PENDING, FAILED }
}

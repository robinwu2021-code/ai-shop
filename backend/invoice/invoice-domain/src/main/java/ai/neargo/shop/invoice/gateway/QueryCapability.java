package ai.neargo.shop.invoice.gateway;

import ai.neargo.shop.invoice.gateway.InvoiceGateway.ProviderAccount;
import java.time.LocalDate;

/** 能向供应商查一张发票的状态，用于对账（TDD-发票模块 §16）。 */
public interface QueryCapability {

    /**
     * 账号无效 / 供应商故障时抛 {@link ProviderException}。
     *
     * @param invoiceDate 开立日（税制时区）
     */
    ProviderInvoiceStatus queryStatus(ProviderAccount account, String fiscalNumber, LocalDate invoiceDate);

    /** 供应商侧发票状态；{@code rawCode} 是供应商原文，便于对照文档。 */
    record ProviderInvoiceStatus(State state, String rawCode) {
        public enum State { ISSUED, VOIDED, PARTIALLY_ALLOWED, FULLY_ALLOWED, NOT_FOUND }
    }
}

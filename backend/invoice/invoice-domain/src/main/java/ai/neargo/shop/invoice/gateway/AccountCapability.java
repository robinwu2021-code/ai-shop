package ai.neargo.shop.invoice.gateway;

import ai.neargo.shop.invoice.gateway.InvoiceGateway.ProviderAccount;

/** 能用商家账号查到供应商侧的营业人资料（鲸跃 QueryOfficeInfo；接入点服务商的参与方查询）。 */
public interface AccountCapability {

    /** 账号无效时抛 {@link ProviderException}。 */
    ProviderOffice verify(ProviderAccount account);

    /** @param qrKey 供应商回传的 QR 加密金钥原文（鲸跃 QRCode）；没给为空 */
    record ProviderOffice(String taxId, String name, String qrKey) {}
}

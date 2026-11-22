package ai.neargo.shop.invoice.channel.base;

import ai.neargo.shop.invoice.gateway.InvoiceGateway.ProviderAccount;
import java.net.URI;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 形态二 · 加密 HTTP 接口（台湾：绿界 ECPay、蓝新 ezPay）。业务报文整体 AES-CBC 加密，外层只有商户号 + 时间戳 + 密文。
 *
 * <p>外层失败（解密失败、商户号不对）不进业务码表：{@link #outer} 回 {@code null} 表示外层拒绝，
 * 此时结果码固定为 {@value #OUTER_REJECTED}，厂商码表把它登记成账号问题。
 */
public abstract class AbstractEncryptedApiInvoiceGateway extends AbstractHttpApiInvoiceGateway {

    public static final String OUTER_REJECTED = "OUTER_REJECTED";

    protected AbstractEncryptedApiInvoiceGateway(URI base, HttpTransport transport, ObjectMapper json) {
        super(base, transport, json);
    }

    /** 厂商的加解密参数（Key / IV 取自账号的 keyId / secret）。 */
    protected abstract AesCbcCipher cipher(ProviderAccount account);

    /** 外层请求（绿界：{@code {MerchantID, RqHeader{Timestamp}, Data}}）。 */
    protected abstract SealedRequest envelope(ProviderAccount account, String cipherText);

    /** 外层响应 → 密文；外层判定失败回 null（绿界 TransCode ≠ 1）。 */
    protected abstract String outer(JsonNode response);

    /** 解密后的业务 JSON → 结果。 */
    protected abstract ApiOutcome business(String operation, JsonNode decrypted);

    @Override
    protected final SealedRequest seal(ProviderAccount account, String operation, Map<String, Object> payload) {
        return envelope(account, cipher(account).encrypt(writeJson(payload)));
    }

    @Override
    protected final ApiOutcome open(ProviderAccount account, String operation, HttpTransport.Response response) {
        String cipherText = outer(readJson(response.body()));
        if (cipherText == null) return new ApiOutcome(OUTER_REJECTED, null, Map.of());
        return business(operation, readJson(cipher(account).decrypt(cipherText)));
    }
}

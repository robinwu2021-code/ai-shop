package ai.neargo.shop.invoice.channel.base;

import ai.neargo.shop.invoice.gateway.InvoiceGateway;
import ai.neargo.shop.invoice.gateway.ProviderException;
import java.util.Locale;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 所有供应商的模板基类（TDD-发票模块 §19）。{@link #send} 是 final：
 * 税制核对 → 账号核对 → 解析报文 → 交给厂商 {@link #upload} → 异常归类。
 *
 * <ul>
 *   <li>不服务该税制 → {@code FINAL}（配置错误，重发也一样）</li>
 *   <li>要账号却没给 → {@code ACCOUNT_PROBLEM}（上传任务据此暂停该主体）</li>
 *   <li>{@link AccountMisconfiguredException}（密钥长度不对、解不开回包）→ {@code ACCOUNT_PROBLEM}</li>
 *   <li>{@link UnsupportedMessageException}（厂商不支持这类票，如鲸跃的零税率）→ {@code FINAL}，不送半对的票</li>
 *   <li>{@link ProviderException}（不可达、HTTP 非 2xx）→ {@code RETRYABLE}，原报文留着下一轮补发；
 *       可能已送达的情况由查单对账兜底（§16），供应商对同一号码的重复上传回「已处理过」</li>
 * </ul>
 *
 * 子类按对接形态再分（SOAP / 加密接口 / 签名接口 / 网络接入点 / 文件交换），厂商类只写映射与码表。
 */
public abstract class AbstractInvoiceGateway implements InvoiceGateway {

    protected final ObjectMapper json;

    protected AbstractInvoiceGateway(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public final GatewayReceipt send(GatewayRequest request) {
        if (!profile().supports(request.regimeCode()))
            return GatewayReceipt.rejected(code() + " does not serve " + request.regimeCode());
        if (profile().requiresAccount() && request.account() == null)
            return GatewayReceipt.accountProblem("provider account not configured");
        try {
            return upload(request, json.readTree(request.payloadJson()));
        } catch (UnsupportedMessageException e) {
            return GatewayReceipt.rejected(e.getMessage());
        } catch (AccountMisconfiguredException e) {
            return GatewayReceipt.accountProblem(code().toLowerCase(Locale.ROOT) + " " + e.getMessage());
        } catch (ProviderException e) {
            return GatewayReceipt.retryable(code().toLowerCase(Locale.ROOT) + " " + e.code());
        }
    }

    /** 把一份报文送出去；{@code message} 是税制生成的报文树。 */
    protected abstract GatewayReceipt upload(GatewayRequest request, JsonNode message);

    /** 厂商的结果码表；没有同步结果码的形态（文件交换）可不覆盖。 */
    protected ResultCodeTable resultCodes() {
        return ResultCodeTable.builder().build();
    }

    /** 按码表把结果码变成回执；成功的回执号形如 {@code CETUSTEK:S0:INV-1}。 */
    protected final GatewayReceipt receipt(String messageType, String resultCode, String refNo) {
        String code = ResultCodeTable.normalize(resultCode);
        String tag = code().toLowerCase(Locale.ROOT) + " " + resultCode;
        return switch (resultCodes().classify(messageType, resultCode)) {
            case ACCEPTED -> GatewayReceipt.accepted(code() + ":" + code + ":" + refNo);
            case RETRYABLE -> GatewayReceipt.retryable(tag);
            case ACCOUNT_PROBLEM -> GatewayReceipt.accountProblem(tag);
            case FINAL -> GatewayReceipt.rejected(tag);
        };
    }

    /** 厂商不支持这类报文（映射阶段拒绝）。 */
    public static class UnsupportedMessageException extends RuntimeException {
        public UnsupportedMessageException(String message) {
            super(message);
        }
    }

    /** 账号里的密钥配错（长度不对、解不开回包）：重发多少次都一样，按账号问题暂停该主体。 */
    public static class AccountMisconfiguredException extends RuntimeException {
        public AccountMisconfiguredException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

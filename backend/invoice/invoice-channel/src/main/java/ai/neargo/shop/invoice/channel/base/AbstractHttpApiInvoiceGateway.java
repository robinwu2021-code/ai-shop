package ai.neargo.shop.invoice.channel.base;

import ai.neargo.shop.invoice.gateway.InvoiceGateway.ProviderAccount;
import java.net.URI;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTPS 请求 / 响应型接口的公共层：地址拼接、传输、模板「封装 → 发送 → 解开」。
 * 怎么封装（整体加密、明文加签、网络文档）由下一层形态类决定，厂商类只写路径、报文映射与码表。
 */
public abstract class AbstractHttpApiInvoiceGateway extends AbstractInvoiceGateway {

    private final URI base;
    private final HttpTransport transport;

    protected AbstractHttpApiInvoiceGateway(URI base, HttpTransport transport, ObjectMapper json) {
        super(json);
        this.base = base;
        this.transport = transport;
    }

    /** 业务路径，如 {@code /B2CInvoice/Issue}；{@code operation} 是报文种类或 QUERY / VERIFY / RANGE。 */
    protected abstract String path(String operation);

    /** 业务报文 → 线上请求。 */
    protected abstract SealedRequest seal(ProviderAccount account, String operation, Map<String, Object> payload);

    /** 线上响应 → 业务结果（结果码、供应商单号、标准数据）。 */
    protected abstract ApiOutcome open(ProviderAccount account, String operation, HttpTransport.Response response);

    protected final ApiOutcome callApi(ProviderAccount account, String operation, Map<String, Object> payload) {
        var request = seal(account, operation, payload);
        var response = transport.post(resolve(path(operation)), request.contentType(), request.body(),
                request.headers());
        return open(account, operation, response);
    }

    private URI resolve(String path) {
        String b = base.toString();
        return URI.create(b.endsWith("/") && path.startsWith("/") ? b + path.substring(1) : b + path);
    }

    protected final JsonNode readJson(String text) {
        return json.readTree(text);
    }

    protected final String writeJson(Object value) {
        return json.writeValueAsString(value);
    }

    public record SealedRequest(String contentType, String body, Map<String, String> headers) {
        public static SealedRequest json(String body, Map<String, String> headers) {
            return new SealedRequest("application/json;charset=UTF-8", body, headers);
        }

        public static SealedRequest form(String body) {
            return new SealedRequest("application/x-www-form-urlencoded;charset=UTF-8", body, Map.of());
        }
    }

    /** @param data 标准键：fiscalNumber / randomCode / issuedAt / status …（领域只认标准键，不认厂商字段名） */
    public record ApiOutcome(String resultCode, String providerRef, Map<String, String> data) {}
}

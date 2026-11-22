package ai.neargo.shop.invoice.channel.base;

import ai.neargo.shop.invoice.gateway.InvoiceGateway.ProviderAccount;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 形态三 · 签名 HTTP 接口（台湾：光贸 Amego；阿联酋多数接入点服务商的 REST API 也属此类）。
 * 报文明文 JSON，签名放在请求头或外层参数里。模板负责序列化与时间戳，厂商类决定签什么、签名放哪、回包怎么读。
 */
public abstract class AbstractSignedApiInvoiceGateway extends AbstractHttpApiInvoiceGateway {

    private final Clock clock;

    protected AbstractSignedApiInvoiceGateway(URI base, HttpTransport transport, ObjectMapper json, Clock clock) {
        super(base, transport, json);
        this.clock = clock;
    }

    /** 签名后的请求（签名算法、待签串、放置位置都是厂商的事）。 */
    protected abstract SealedRequest signed(ProviderAccount account, String operation, String body, long epochSeconds);

    /** 回包 → 结果；需要验签的厂商在这里先验。 */
    protected abstract ApiOutcome business(ProviderAccount account, String operation, JsonNode body,
            HttpTransport.Response response);

    @Override
    protected final SealedRequest seal(ProviderAccount account, String operation, Map<String, Object> payload) {
        return signed(account, operation, writeJson(payload), clock.instant().getEpochSecond());
    }

    @Override
    protected final ApiOutcome open(ProviderAccount account, String operation, HttpTransport.Response response) {
        return business(account, operation, readJson(response.body()), response);
    }

    /** HMAC-SHA256，小写十六进制。 */
    protected static String hmacSha256Hex(String key, String data) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}

package ai.neargo.shop.invoice.channel.base;

import ai.neargo.shop.invoice.gateway.InvoiceGateway.ProviderAccount;
import ai.neargo.shop.invoice.gateway.ProviderException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * 形态一 · SOAP Web Service（台湾：鲸跃 InvoiceEntitySync）。管信封、命名空间、认证参数在前的参数顺序、取 {@code <return>}。
 * 厂商类只给命名空间、认证参数怎么摆，以及各操作的参数与回包解析。
 */
public abstract class AbstractSoapInvoiceGateway extends AbstractInvoiceGateway {

    private final URI endpoint;
    private final HttpTransport transport;

    protected AbstractSoapInvoiceGateway(URI endpoint, HttpTransport transport, ObjectMapper json) {
        super(json);
        this.endpoint = endpoint;
        this.transport = transport;
    }

    /** 厂商命名空间，如 {@code http://webservice.cetustek.com/}。 */
    protected abstract String namespace();

    /**
     * 额外请求头。经隧道 / 代理访问按虚拟主机区分站点的厂商时用来带 {@code Host}（JDK HttpClient 默认禁止改 Host，
     * 要同时以 {@code -Djdk.httpclient.allowRestrictedHeaders=host} 启动）。默认没有。
     */
    protected Map<String, String> extraHeaders() {
        return Map.of();
    }

    /** 每个操作都带的认证参数，按顺序（鲸跃：officeid / posno / posid）。 */
    protected abstract LinkedHashMap<String, String> authFields(ProviderAccount account);

    /**
     * 调一个 SOAP 操作，返回 {@code <return>} 的原文（已反转义、去首尾空白）；空回包 {@code <return/>} 为空串。
     * 回包没有 {@code <return>} 抛 {@link ProviderException}（{@code no_return}）。
     */
    protected final String soap(String operation, ProviderAccount account, Map<String, String> fields) {
        var all = authFields(account);
        all.putAll(fields);
        String envelope = SoapEnvelope.build(namespace(), operation, all);
        String body = transport.post(endpoint, "text/xml;charset=UTF-8", envelope, extraHeaders()).body();
        traceWire(operation, account, envelope, body);
        String result = SoapEnvelope.between(body, "<return>", "</return>");
        if (result == null) {
            if (body.contains("<return/>")) return "";
            throw new ProviderException("no_return", null);
        }
        return SoapEnvelope.unescape(result).trim();
    }
}

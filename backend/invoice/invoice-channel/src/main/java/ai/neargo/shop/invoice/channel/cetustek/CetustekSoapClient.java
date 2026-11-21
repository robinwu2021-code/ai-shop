package ai.neargo.shop.invoice.channel.cetustek;

import ai.neargo.shop.invoice.gateway.InvoiceGateway.ProviderAccount;
import ai.neargo.shop.invoice.gateway.InvoiceProviderGateway.ProviderException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 鯨躍 InvoiceEntitySync 的 SOAP 调用（沿用 UME invoice-web {@code CetustekInvoiceServiceHelper} 的报文形状）：
 *
 * <pre>{@code
 * <soapenv:Envelope xmlns:soapenv=… xmlns:pm="http://webservice.cetustek.com/">
 *   <soapenv:Body><pm:UploadInvoice><officeid/><posno/><posid/><invoicexml>转义后的发票 XML</invoicexml></pm:UploadInvoice>
 * }</pre>
 *
 * 回包取 {@code <return>} 的文本（反转义一次）：上传类是 S0 / C0 / A0 之类的结果码，查询类是一段 XML 或以分号分隔的值。
 * HTTP 非 200 或没有 {@code <return>} 视为不可达，抛 {@link ProviderException}，由调用方决定重试。
 */
class CetustekSoapClient {

    private static final String NS = "http://webservice.cetustek.com/";

    private final URI endpoint;
    private final HttpClient http;
    private final Duration timeout;

    CetustekSoapClient(URI endpoint, Duration timeout) {
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /**
     * @param fields 操作参数，按顺序输出；值按 XML 文本转义（内层发票 XML 因此整体成为一段转义文本）
     * @return {@code <return>} 的原文（已反转义）
     */
    String call(String operation, ProviderAccount account, Map<String, String> fields) {
        var all = new LinkedHashMap<String, String>();
        all.put("officeid", account.accountCode());
        all.put("posno", account.user());
        all.put("posid", account.password());
        all.putAll(fields);
        String body = envelope(operation, all);
        HttpResponse<String> response;
        try {
            response = http.send(HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Content-Type", "text/xml;charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ProviderException("unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("interrupted", e);
        }
        if (response.statusCode() != 200) throw new ProviderException("http_" + response.statusCode(), null);
        String result = between(response.body(), "<return>", "</return>");
        if (result == null) {
            // 空回包写成 <return/>
            if (response.body().contains("<return/>")) return "";
            throw new ProviderException("no_return", null);
        }
        return unescape(result).trim();
    }

    static String envelope(String operation, Map<String, String> fields) {
        var out = new StringBuilder("<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\"")
                .append(" xmlns:pm=\"").append(NS).append("\"><soapenv:Header></soapenv:Header><soapenv:Body>")
                .append("<pm:").append(operation).append('>');
        fields.forEach((k, v) -> out.append('<').append(k).append('>').append(escape(v == null ? "" : v))
                .append("</").append(k).append('>'));
        return out.append("</pm:").append(operation).append("></soapenv:Body></soapenv:Envelope>").toString();
    }

    static String between(String s, String open, String close) {
        int i = s.indexOf(open);
        if (i < 0) return null;
        int j = s.indexOf(close, i + open.length());
        return j < 0 ? null : s.substring(i + open.length(), j);
    }

    static String escape(String s) {
        var out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> {
                    // XML 1.0 不允许的控制字符直接丢掉，免得整张报文被对方判 M1（XML 格式错误）
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') out.append(c);
                }
            }
        }
        return out.toString();
    }

    static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&#xD;", "\r").replace("&#13;", "\r").replace("&amp;", "&");
    }
}

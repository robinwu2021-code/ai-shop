package ai.neargo.shop.invoice.channel.base;

import ai.neargo.shop.invoice.gateway.InvoiceGateway.ProviderAccount;
import ai.neargo.shop.invoice.gateway.ProviderException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 形态 · 明文 JSON POS 接口（台湾：汎宇 einvoice.com.tw）。单一地址、按报文里的功能码区分动作，
 * 账号凭据写在报文体里，没有签名或整体加密；回包统一包在 {@code {"<回包根>":{…}}} 里。
 *
 * <p>本层管：POST JSON、取回包根、空值归一（{@code null} / {@code "null"} / 空串都当没有）、
 * 结果码缺省时按「回复标志」归一（成功 → {@link #successCode()}，否则 {@code REPLY<标志>}）、原文留痕打码。
 * 厂商类只给回包根与字段名、报文映射、码表。
 */
public abstract class AbstractPlainJsonInvoiceGateway extends AbstractHttpApiInvoiceGateway {

    protected AbstractPlainJsonInvoiceGateway(URI endpoint, HttpTransport transport, ObjectMapper json) {
        super(endpoint, transport, json);
    }

    /** 回包根节点名（汎宇 {@code INDEX}）。 */
    protected abstract String replyRoot();

    /** 结果码字段（汎宇 {@code ERROR_CODE}）。 */
    protected abstract String codeField();

    /** 回复标志字段（汎宇 {@code REPLY}：1 成功，0 / -1 失败）与其成功值。 */
    protected abstract String replyField();

    protected String replySuccessValue() {
        return "1";
    }

    /** 回复成功而没给结果码时用的码（汎宇 {@code 0000}）。 */
    protected abstract String successCode();

    /** 供应商单号字段（汎宇 {@code INVOICENUMBER}）；没有返回 null。 */
    protected String referenceField() {
        return null;
    }

    /** 单一地址，动作靠报文里的功能码区分。 */
    @Override
    protected final String path(String operation) {
        return "";
    }

    @Override
    protected final SealedRequest seal(ProviderAccount account, String operation, Map<String, Object> payload) {
        return SealedRequest.json(writeJson(payload), Map.of());
    }

    @Override
    protected final ApiOutcome open(ProviderAccount account, String operation, HttpTransport.Response response) {
        JsonNode root = readJson(response.body()).path(replyRoot());
        if (!root.isObject()) throw new ProviderException("unexpected", null);
        String reply = value(root, replyField());
        String code = value(root, codeField());
        if (code == null) code = replySuccessValue().equals(reply) ? successCode() : "REPLY" + (reply == null ? "" : reply);
        var data = new LinkedHashMap<String, String>();
        for (var e : root.properties()) {
            String v = value(root, e.getKey());
            if (v != null && e.getValue().isValueNode()) data.put(e.getKey(), v);
        }
        String ref = referenceField() == null ? null : value(root, referenceField());
        return new ApiOutcome(code, ref, data);
    }

    /** 送一份报文并把回包标准化；本层记原文（打码）。 */
    protected final ProviderReply exchange(ProviderAccount account, String operation, Map<String, Object> payload) {
        var outcome = callApi(account, operation, payload);
        return new ProviderReply(outcome.resultCode(), outcome.providerRef(), outcome.data(), null);
    }

    /** 回包里的空值有 {@code null}、{@code "null"}、空串三种写法，一律当没有。 */
    protected static String value(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText().trim();
        return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
    }
}

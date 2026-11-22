package ai.neargo.shop.invoice.channel.base;

import ai.neargo.shop.invoice.gateway.InvoiceGateway.ProviderAccount;
import ai.neargo.shop.invoice.gateway.ProviderProfile;
import java.net.URI;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 形态四 · 网络接入点（阿联酋：Peppol 五角模型下的认证接入点服务商 ASP；欧盟同理）。
 *
 * <p>我们把税制报文转成网络文档（阿联酋 PINT AE 的 UBL），交给自己的接入点：接入点负责投递给买方的接入点、
 * 并把税务数据报给税务机关。<b>受理即送达</b>（{@link ProviderProfile.ReportMode#NETWORK_DELIVERY}），
 * 最终投递结果异步回来，由查单能力对账。
 *
 * <p>请求外层固定为 {@code {senderId, documentType, messageType, refNo, document}}；{@code senderId} 是账号代号
 * （营业人在网络里的参与方标识，阿联酋为 TRN 派生）。认证一律走 {@link AbstractSignedApiInvoiceGateway} 的签名钩子。
 */
public abstract class AbstractNetworkInvoiceGateway extends AbstractSignedApiInvoiceGateway {

    protected AbstractNetworkInvoiceGateway(URI base, HttpTransport transport, ObjectMapper json, Clock clock) {
        super(base, transport, json, clock);
    }

    /** 报文种类 → 网络文档类型（开立 → 发票；折让 → 贷项通知 …）；不支持的种类抛 {@link UnsupportedMessageException}。 */
    protected abstract String documentType(String messageType);

    /** 税制报文 → 网络文档（UBL XML 或接入点约定的 JSON 文档）。 */
    protected abstract Object toNetworkDocument(String messageType, JsonNode message);

    /** 接入点的受理结果 → 结果码与受理号。 */
    protected abstract ApiOutcome accepted(String operation, JsonNode body);

    @Override
    protected final GatewayReceipt upload(GatewayRequest request, JsonNode message) {
        String type = request.messageType();
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("senderId", request.account().accountCode());
        envelope.put("documentType", documentType(type));
        envelope.put("messageType", type);
        envelope.put("refNo", request.refNo());
        envelope.put("document", toNetworkDocument(type, message));
        var outcome = callApi(request.account(), type, envelope);
        return receipt(type, outcome.resultCode(),
                outcome.providerRef() == null ? request.refNo() : outcome.providerRef());
    }

    @Override
    protected final ApiOutcome business(ProviderAccount account, String operation, JsonNode body,
            HttpTransport.Response response) {
        return accepted(operation, body);
    }

    /** 网络接入点只需账号、都服务异步投递；子类给税制与编号方式。 */
    protected static ProviderProfile networkProfile(java.util.Set<String> regimes,
            ProviderProfile.NumberingMode numbering) {
        return new ProviderProfile(regimes, numbering, ProviderProfile.ReportMode.NETWORK_DELIVERY, true);
    }

    /** 便于子类组装标准数据。 */
    protected static Map<String, String> data(String... kv) {
        var m = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) if (kv[i + 1] != null) m.put(kv[i], kv[i + 1]);
        return m;
    }
}

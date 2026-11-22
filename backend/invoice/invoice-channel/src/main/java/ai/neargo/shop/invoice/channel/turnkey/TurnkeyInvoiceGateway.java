package ai.neargo.shop.invoice.channel.turnkey;

import ai.neargo.shop.invoice.channel.base.AbstractFileExchangeInvoiceGateway;
import ai.neargo.shop.invoice.gateway.ProviderProfile;
import ai.neargo.shop.invoice.gateway.ProviderProfile.NumberingMode;
import ai.neargo.shop.invoice.gateway.ProviderProfile.ReportMode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 财政部 Turnkey，台湾 · 文件交换形态（{@link AbstractFileExchangeInvoiceGateway}）：把 MIG 报文写成 XML 文件放进
 * Turnkey 的上传目录，由 Turnkey 程序负责签章与上传大平台。不需要与任何加值中心签约，号段由商家自领、在 B 端登记。
 *
 * <p><b>目录</b>：{@code shop.invoice.turnkey.root} 是 Turnkey 安装时配置的数据根；子路径按报文代号替换
 * {@code {mig}}，默认 {@code UpCast/B2SSTORAGE/{mig}/SRC}，与所装 Turnkey 版本的目录设置不一致时改配置即可。
 * 文件名 = 报文代号 + 业务号；原子落盘与重发覆盖由基类保证。
 * 「送达」只表示文件已交给 Turnkey；大平台的处理结果由 Turnkey 的回执目录给出，接回执是下一步。
 */
@Component
@ConditionalOnProperty(name = "shop.invoice.turnkey.root")
public class TurnkeyInvoiceGateway extends AbstractFileExchangeInvoiceGateway {

    static final ProviderProfile PROFILE =
            new ProviderProfile(Set.of("TW_EGUI"), NumberingMode.LOCAL_RANGE, ReportMode.FILE_DROP, false);

    private static final Map<String, String> ROOT_ELEMENT =
            Map.of("F0401", "Invoice", "F0501", "CancelInvoice", "G0401", "Allowance", "G0501", "CancelAllowance");

    private final Path root;
    private final String pathPattern;

    public TurnkeyInvoiceGateway(@Value("${shop.invoice.turnkey.root}") String root,
            @Value("${shop.invoice.turnkey.path-pattern:UpCast/B2SSTORAGE/{mig}/SRC}") String pathPattern,
            ObjectMapper json) {
        super(json);
        this.root = Path.of(root);
        this.pathPattern = pathPattern;
    }

    @Override
    public String code() {
        return "TURNKEY";
    }

    @Override
    public ProviderProfile profile() {
        return PROFILE;
    }

    @Override
    protected ExchangeFile render(GatewayRequest request, JsonNode message) {
        String mig = message.path("mig").asText();
        String rootElement = ROOT_ELEMENT.get(mig);
        if (rootElement == null) throw new UnsupportedMessageException("Unsupported MIG message: " + mig);
        String id = switch (mig) {
            case "F0401" -> message.path("Main").path("InvoiceNumber").asText();
            case "F0501" -> message.path("CancelInvoiceNumber").asText();
            case "G0501" -> message.path("CancelAllowanceNumber").asText();
            default -> message.path("Main").path("AllowanceNumber").asText();
        };
        Path dir = root.resolve(pathPattern.replace("{mig}", mig));
        return new ExchangeFile(dir.resolve(mig + "_" + id + ".xml"), xml(rootElement, mig, message));
    }

    /**
     * 报文树 → MIG XML。对象成元素，数组成同名重复元素；顶层 {@code mig} 只决定命名空间、{@code ext} 是给加值中心的
     * 补充字段（MIG 之外），都不输出。
     */
    static String xml(String rootElement, String mig, JsonNode message) {
        var out = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        out.append('<').append(rootElement).append(" xmlns=\"urn:GEINV:eInvoiceMessage:").append(mig)
                .append(":4.0\">");
        for (var name : List.copyOf(message.propertyNames()))
            if (!name.equals("mig") && !name.equals("ext")) element(out, name, message.get(name));
        return out.append("</").append(rootElement).append(">\n").toString();
    }

    private static void element(StringBuilder out, String name, JsonNode value) {
        if (value == null || value.isNull()) return;
        if (value.isArray()) {
            for (var item : value) element(out, name, item);
            return;
        }
        out.append('<').append(name).append('>');
        if (value.isObject()) for (var child : List.copyOf(value.propertyNames())) element(out, child, value.get(child));
        else out.append(escape(value.asText()));
        out.append("</").append(name).append('>');
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}

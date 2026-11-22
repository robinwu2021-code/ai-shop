package ai.neargo.shop.invoice.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.invoice.entity.IvcRegimeProfile;
import ai.neargo.shop.invoice.gateway.InvoiceGateway;
import ai.neargo.shop.invoice.gateway.InvoiceGateways;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 用哪家开票供应商（TDD-发票模块 §20）：系统统一决定，三层 ——
 *
 * <ol>
 *   <li><b>默认市场</b> {@code shop.invoice.default-regime}（缺省 {@code TW_EGUI}）：商家开通时不指定市场就用它</li>
 *   <li><b>市场默认供应商</b> {@code shop.invoice.regimes.<市场>.default-gateway}（缺省 {@code STUB}）：
 *       主体没选供应商就走它；换阶段（测试桩 → 鲸跃 → …）只改这一项，没选过的主体一起切</li>
 *   <li><b>商家选择</b>：从迁移过来的商户可能已在用某家供应商，可在「该市场支持的供应商」里自选，存在主体配置上，优先于默认</li>
 * </ol>
 *
 * 「支持的供应商」= {@code shop.invoice.regimes.<市场>.gateways}（逗号分隔）里已装配的；没配则取所有声明服务该市场的，
 * 默认供应商总在其中。同一市场可以多家同时在用：每条上传任务记着自己走的那家，开出后不随切换改走别家。
 */
@Component
public class InvoiceGatewayPolicy {

    public static final String SOURCE_MERCHANT = "MERCHANT";
    public static final String SOURCE_DEFAULT = "DEFAULT";

    private final InvoiceGateways gateways;
    private final Environment env;

    public InvoiceGatewayPolicy(InvoiceGateways gateways, Environment env) {
        this.gateways = gateways;
        this.env = env;
    }

    public String defaultRegime() {
        return env.getProperty("shop.invoice.default-regime", "TW_EGUI");
    }

    public String defaultGateway(String regimeCode) {
        return env.getProperty("shop.invoice.regimes." + regimeCode + ".default-gateway", "STUB");
    }

    /** 该市场商家可选的供应商（已装配），默认供应商排第一。 */
    public List<InvoiceGateway> selectable(String regimeCode) {
        String def = defaultGateway(regimeCode);
        String configured = env.getProperty("shop.invoice.regimes." + regimeCode + ".gateways");
        List<InvoiceGateway> candidates = configured == null || configured.isBlank() ? gateways.forRegime(regimeCode)
                : Arrays.stream(configured.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                        .map(gateways::find).flatMap(java.util.Optional::stream).toList();
        var out = new ArrayList<InvoiceGateway>();
        gateways.find(def).ifPresent(out::add);
        for (var g : candidates) if (!g.code().equals(def)) out.add(g);
        return out;
    }

    /** 商家选的须在该市场支持的供应商里，否则 26012（{0} = 供应商编码）。 */
    public void requireSelectable(String regimeCode, String gatewayCode) {
        if (selectable(regimeCode).stream().noneMatch(g -> g.code().equals(gatewayCode)))
            throw BizException.of(ErrorCode.INVOICE_GATEWAY_UNSUPPORTED, gatewayCode);
    }

    /** 主体当前生效的供应商：商家选了用商家的，否则用市场默认。 */
    public Effective effective(IvcRegimeProfile p) {
        return p.getGatewayCode() != null ? new Effective(p.getGatewayCode(), SOURCE_MERCHANT)
                : new Effective(defaultGateway(p.getRegimeCode()), SOURCE_DEFAULT);
    }

    /** @param source {@value #SOURCE_MERCHANT} 商家选择 / {@value #SOURCE_DEFAULT} 系统默认 */
    public record Effective(String code, String source) {}
}

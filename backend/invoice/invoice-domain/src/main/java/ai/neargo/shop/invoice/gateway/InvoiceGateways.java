package ai.neargo.shop.invoice.gateway;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 已装配的供应商（TDD-发票模块 §19）。领域只经这里找供应商、按能力取用，不认具体厂商类。
 * 编码重复时启动失败（toMap 遇重复键抛异常），不静默覆盖。
 */
@Component
public class InvoiceGateways {

    private final Map<String, InvoiceGateway> byCode;

    public InvoiceGateways(List<InvoiceGateway> gateways) {
        byCode = gateways.stream().collect(Collectors.toMap(InvoiceGateway::code, Function.identity()));
    }

    public Optional<InvoiceGateway> find(String code) {
        return Optional.ofNullable(code == null ? null : byCode.get(code));
    }

    /** 供应商有这项能力才返回；没装或没有该能力为空。 */
    public <T> Optional<T> capability(String code, Class<T> type) {
        return find(code).filter(type::isInstance).map(type::cast);
    }

    /**
     * 供应商的能力集合（TDD-发票-供应商能力定义完善 D1）：由实现的能力接口与声明的上传报文推导；没装为空。
     * 注销要两样都有：收 REVOKE 上传报文、能查注销进度。
     */
    public Set<ProviderCapability> capabilities(String code) {
        return find(code).map(InvoiceGateways::capabilitiesOf).orElse(Set.of());
    }

    public boolean has(String code, ProviderCapability capability) {
        return capabilities(code).contains(capability);
    }

    static Set<ProviderCapability> capabilitiesOf(InvoiceGateway g) {
        var out = EnumSet.noneOf(ProviderCapability.class);
        if (g instanceof AccountCapability) out.add(ProviderCapability.VERIFY_ACCOUNT);
        if (g instanceof RangeCapability) out.add(ProviderCapability.ALLOCATE_RANGE);
        if (g instanceof QueryCapability) out.add(ProviderCapability.QUERY_STATUS);
        if (g instanceof TrackCapability) out.add(ProviderCapability.MANAGE_TRACKS);
        if (g instanceof BlankRangeCapability) out.add(ProviderCapability.REPORT_BLANK);
        if (g instanceof RevokeStatusCapability && g.profile().accepts(ProviderOperation.REVOKE))
            out.add(ProviderCapability.REVOKE);
        return Set.copyOf(out);
    }

    public boolean requiresAccount(String code) {
        return find(code).map(g -> g.profile().requiresAccount()).orElse(false);
    }

    /** 声明服务某税制（地区）的供应商，按编码排序 —— 供系统指定 / 商家选择时列出候选，不做强制校验。 */
    public List<InvoiceGateway> forRegime(String regimeCode) {
        return byCode.values().stream().filter(g -> g.profile().supports(regimeCode))
                .sorted(Comparator.comparing(InvoiceGateway::code)).toList();
    }
}

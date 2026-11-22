package ai.neargo.shop.invoice.gateway;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    public boolean requiresAccount(String code) {
        return find(code).map(g -> g.profile().requiresAccount()).orElse(false);
    }

    /** 声明服务某税制（地区）的供应商，按编码排序 —— 供系统指定 / 商家选择时列出候选，不做强制校验。 */
    public List<InvoiceGateway> forRegime(String regimeCode) {
        return byCode.values().stream().filter(g -> g.profile().supports(regimeCode))
                .sorted(Comparator.comparing(InvoiceGateway::code)).toList();
    }
}

package ai.neargo.shop.invoice.regime;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** 已装配的税制。编码重复时启动失败（toMap 遇重复键抛异常），不静默覆盖。 */
@Component
public class InvoiceRegimes {

    private final Map<String, InvoiceRegime> byCode;

    public InvoiceRegimes(List<InvoiceRegime> regimes) {
        byCode = regimes.stream().collect(Collectors.toMap(InvoiceRegime::code, Function.identity()));
    }

    public InvoiceRegime require(String code) {
        var regime = byCode.get(code);
        if (regime == null) throw BizException.of(ErrorCode.INVOICE_REGIME_NOT_ENABLED);
        return regime;
    }
}

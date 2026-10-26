package ai.neargo.shop.pay.funds;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** 按支付单冻结的 {@code funds_path} 取分录规则；未冻结（存量单）为空 —— 不猜，交给回填（B7） */
@Component
public class FundsPaths {

    private final Map<String, FundsPathStrategy> byPath;

    public FundsPaths(List<FundsPathStrategy> strategies) {
        this.byPath = strategies.stream().collect(Collectors.toMap(FundsPathStrategy::fundsPath, Function.identity()));
    }

    public Optional<FundsPathStrategy> of(String fundsPath) {
        return Optional.ofNullable(fundsPath == null ? null : byPath.get(fundsPath));
    }
}

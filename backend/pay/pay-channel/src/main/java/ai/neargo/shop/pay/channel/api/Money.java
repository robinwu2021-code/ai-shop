package ai.neargo.shop.pay.channel.api;

import java.util.Objects;

/** 金额一律最小单位 + 币种；发给渠道的表示由 {@code amount_scale} 另算（渠道接入统一封装设计 §2.4、§11.2） */
public record Money(long minor, String currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (!currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("币种必须是 3 位大写 ISO 代码：" + currency);
        }
    }
}

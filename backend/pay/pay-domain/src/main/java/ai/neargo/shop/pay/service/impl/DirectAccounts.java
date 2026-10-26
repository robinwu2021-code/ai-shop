package ai.neargo.shop.pay.service.impl;

import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.user.MerchantQueryPort.PaymentRoute;
import java.util.Optional;

/**
 * 一笔收款实际进的那个商户账户（DIRECT 路径）。下单、收款落库、退款**同一条规则**（F08 · Review R08）：
 * DIRECT 取第一家门店的路由（{@code PaymentLedgerServiceImpl#validateRequest} 已保证 DIRECT 只有一个账户）；
 * AGGREGATED 钱进平台自己的号，没有子商户。
 */
final class DirectAccounts {

    private DirectAccounts() {
    }

    static Optional<PaymentRoute> of(SettlePort.PaymentOpen request) {
        if (request == null || request.stores().isEmpty()) {
            return Optional.empty();
        }
        PaymentRoute route = request.stores().getFirst().route();
        return route != null && "DIRECT".equals(route.fundsPath()) ? Optional.of(route) : Optional.empty();
    }
}

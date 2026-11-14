package ai.neargo.shop.pay.acquiring.route;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.channel.entity.PayChannelProduct;
import ai.neargo.shop.pay.mapper.ChannelMappers.PayChannelProductMapper;
import ai.neargo.shop.spi.pay.PayMerchantQueryPort;
import ai.neargo.shop.spi.pay.PayMerchantQueryPort.MerchantCapability;
import ai.neargo.shop.spi.pay.PayMerchantQueryPort.PayRoute;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 选路由（I3 偏差 10 / I4 偏差 18；TDD-支付服务-I5接入层 S4）：门店能收款的路由（只按门店绑定，没有兜底 R01）×
 * 启用中的支付产品（渠道 × 接入模式 × 支付方式）× 该收款商户已开通的产品，按收款商户优先级取第一条。
 */
@Component
public class RouteSelectionPolicy {

    /** 选中的路由与支付产品 */
    public record Selected(PayRoute route, PayChannelProduct product) {
    }

    private final PayMerchantQueryPort merchants;
    private final PayChannelProductMapper products;

    public RouteSelectionPolicy(PayMerchantQueryPort merchants, PayChannelProductMapper products) {
        this.merchants = merchants;
        this.products = products;
    }

    public Optional<Selected> select(Long storeId, String market, String payMethod) {
        return available(storeId, market).stream().filter(s -> s.product().getPayMethod().equals(payMethod)).findFirst();
    }

    /** 门店此刻能用的全部（路由 × 产品），已按优先级排好 —— 收银台与「可用支付方式」都看它 */
    public List<Selected> available(Long storeId, String market) {
        List<Selected> out = new ArrayList<>();
        for (PayRoute r : merchants.collectableRoutes(storeId, market)) {
            MerchantCapability cap = merchants.capabilityOf(r.payMerchantId()).orElse(null);
            if (cap == null) {
                continue;
            }
            List<PayChannelProduct> candidates = DataScopeContext.executeWithoutScope(() -> products.selectList(
                    Wrappers.<PayChannelProduct>lambdaQuery().eq(PayChannelProduct::getPayChannel, r.payChannel())
                            .eq(PayChannelProduct::getAccessMode, r.accessMode())
                            .eq(PayChannelProduct::getStatus, "ENABLED")
                            .orderByAsc(PayChannelProduct::getSortNo)));
            for (PayChannelProduct p : candidates) {
                if (cap.payChannelProductIds().contains(p.getId())) {
                    out.add(new Selected(r, p));
                }
            }
        }
        return out;
    }
}

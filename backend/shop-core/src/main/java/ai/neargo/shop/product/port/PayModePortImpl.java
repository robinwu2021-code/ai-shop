package ai.neargo.shop.product.port;

import ai.neargo.shop.product.service.PayModeService;
import ai.neargo.shop.spi.product.PayModePort;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * {@link PayModePort} 的实现 —— 薄转发，判定逻辑仍然只有 {@link PayModeService} 一份。
 *
 * <p>不把四层取交集的逻辑搬过来：搬了就会有两份会慢慢分叉的实现，
 * 而「支付方式为什么少了一种」是最难查的一类问题。
 */
@Component
public class PayModePortImpl implements PayModePort {

    private final PayModeService payModeService;

    public PayModePortImpl(PayModeService payModeService) {
        this.payModeService = payModeService;
    }

    @Override
    public Set<String> availablePayModes(String goodsNo, String storeNo) {
        return payModeService.availablePayModes(goodsNo, storeNo);
    }
}

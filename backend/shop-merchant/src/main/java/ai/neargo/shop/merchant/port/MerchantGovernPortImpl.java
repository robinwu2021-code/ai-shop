package ai.neargo.shop.merchant.port;

import ai.neargo.shop.merchant.service.MerchantGovernService;
import ai.neargo.shop.spi.user.MerchantGovernPort;
import org.springframework.stereotype.Component;

/**
 * {@link MerchantGovernPort} 实现：转调 {@link MerchantGovernService#recordViolation}。
 *
 * <p>薄得像没有——价值不在做了什么，而在**别的域只认这个接口**：
 * 违规的分级规则、对信用分的影响、申诉入口将来怎么变，都在商家域里改，
 * 营销域与商品域一行不用动。
 */
@Component
public class MerchantGovernPortImpl implements MerchantGovernPort {

    private final MerchantGovernService governService;

    public MerchantGovernPortImpl(MerchantGovernService governService) {
        this.governService = governService;
    }

    @Override
    public void record(String merchantNo, String type, String action, String detail, String operatorNo) {
        // Port 的调用方都是主体级处置（风控/营销的违规上报），门店号恒空
        governService.recordViolation(merchantNo, null, type, action, detail, operatorNo);
    }
}

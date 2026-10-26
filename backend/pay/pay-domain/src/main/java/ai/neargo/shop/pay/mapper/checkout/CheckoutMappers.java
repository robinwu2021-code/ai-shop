package ai.neargo.shop.pay.mapper.checkout;

import ai.neargo.shop.pay.checkout.entity.PayQrCode;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 收银台的 mapper（db/pay V11 · TDD-支付服务-I5接入层 S1） */
public final class CheckoutMappers {

    private CheckoutMappers() {
    }

    public interface PayQrCodeMapper extends BaseMapper<PayQrCode> {
    }
}

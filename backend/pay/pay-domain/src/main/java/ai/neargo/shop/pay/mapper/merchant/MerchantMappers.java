package ai.neargo.shop.pay.mapper.merchant;

import ai.neargo.shop.common.config.PayMerchantTableMapper;
import ai.neargo.shop.pay.merchant.entity.PayMerchant;
import ai.neargo.shop.pay.merchant.entity.PayMerchantBinding;
import ai.neargo.shop.pay.merchant.entity.PayMerchantProduct;
import ai.neargo.shop.pay.merchant.entity.PayMerchantStatusLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 收款商户与门店绑定的 mapper（db/pay V10 · TDD-支付服务-I4进件与收款商户 S1）。包名由装配决定，见 {@link ai.neargo.shop.pay.mapper.ChannelMappers} */
public final class MerchantMappers {

    private MerchantMappers() {
    }

    public interface PayMerchantMapper extends PayMerchantTableMapper<PayMerchant> {
    }

    public interface PayMerchantBindingMapper extends PayMerchantTableMapper<PayMerchantBinding> {
    }

    public interface PayMerchantProductMapper extends PayMerchantTableMapper<PayMerchantProduct> {
    }

    public interface PayMerchantStatusLogMapper extends BaseMapper<PayMerchantStatusLog> {
    }
}

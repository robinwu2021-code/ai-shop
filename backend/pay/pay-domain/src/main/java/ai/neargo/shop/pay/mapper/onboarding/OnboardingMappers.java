package ai.neargo.shop.pay.mapper.onboarding;

import ai.neargo.shop.common.config.PayMerchantTableMapper;
import ai.neargo.shop.pay.onboarding.entity.PayOnboarding;
import ai.neargo.shop.pay.onboarding.entity.PayOnboardingMaterial;
import ai.neargo.shop.pay.onboarding.entity.PayOnboardingVersion;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 进件的 mapper（db/pay V10 · TDD-支付服务-I4进件与收款商户 S1） */
public final class OnboardingMappers {

    private OnboardingMappers() {
    }

    public interface PayOnboardingMapper extends PayMerchantTableMapper<PayOnboarding> {
    }

    public interface PayOnboardingVersionMapper extends PayMerchantTableMapper<PayOnboardingVersion> {
    }

    public interface PayOnboardingMaterialMapper extends PayMerchantTableMapper<PayOnboardingMaterial> {
    }
}

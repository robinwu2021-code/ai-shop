package ai.neargo.shop.merchant.paymerchant.mapper;

import ai.neargo.shop.merchant.paymerchant.entity.MchPaymentMerchant;
import ai.neargo.shop.merchant.paymerchant.entity.MchPayoutAccount;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 商户域这一侧的收款商户与出款账户。
 *
 * <p>收款商户现在有三份：这里的旧表、进程内支付域的 {@code pay_merchant}、独立支付服务 near-pay 的。
 * 以谁为准还没定（商户域设计方案评审 R8）。在那之前，这里的 Mapper 只在 {@code merchant.paymerchant} 包内用，
 * 商户核心经 {@link ai.neargo.shop.merchant.paymerchant.CollectingMerchants} 访问 ——
 * 定下来之后只换那一个实现。
 */
public final class PayMerchantMappers {

    private PayMerchantMappers() {
    }

    /** 商家支付进件：每通道一条。分账回调只带 sub_mchid，靠 idx_mp_sub_mchid 反查商家。 */
    public interface MchPaymentMapper extends BaseMapper<MchPaymentMerchant> {
    }

    /**
     * 供应商收款账户（V358，ADR-011）。
     *
     * <p>只做单表 CRUD —— 「同一主体只能有一个 ACTIVE」那条约束在 Service，
     * 不在这里：它要跨行判断并改另一行的状态，放 Mapper 就绕过了状态机。
     */
    public interface PayoutAccountMapper extends BaseMapper<MchPayoutAccount> {
    }
}

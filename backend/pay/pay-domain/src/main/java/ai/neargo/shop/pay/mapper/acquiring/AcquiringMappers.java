package ai.neargo.shop.pay.mapper.acquiring;

import ai.neargo.shop.pay.acquiring.entity.PayCheckoutSession;
import ai.neargo.shop.pay.acquiring.entity.PayException;
import ai.neargo.shop.pay.acquiring.entity.PayOrder;
import ai.neargo.shop.pay.acquiring.entity.PayRefund;
import ai.neargo.shop.pay.acquiring.entity.PayRefundReviewRule;
import ai.neargo.shop.pay.approval.entity.PayApproval;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 支付单、退款与复核的 mapper（db/pay V8 · TDD-支付服务-I3支付单退款流水 S1）。
 * 放在 {@code ai.neargo.shop.pay.mapper} 下的理由见 {@link ai.neargo.shop.pay.mapper.ChannelMappers}：装配决定包名。
 */
public final class AcquiringMappers {

    private AcquiringMappers() {
    }

    public interface PayOrderMapper extends BaseMapper<PayOrder> {

        /** 推进状态与金额前先锁：回调、补查、关单、退款占用都会同时到 */
        @Select("SELECT * FROM pay_order WHERE id = #{id} AND deleted = 0 FOR UPDATE")
        PayOrder lockById(@Param("id") Long id);
    }

    public interface PayRefundMapper extends BaseMapper<PayRefund> {

        @Select("SELECT * FROM pay_refund WHERE id = #{id} AND deleted = 0 FOR UPDATE")
        PayRefund lockById(@Param("id") Long id);
    }

    public interface RefundReviewRuleMapper extends BaseMapper<PayRefundReviewRule> {
    }

    public interface PayApprovalMapper extends BaseMapper<PayApproval> {
    }

    public interface PayExceptionMapper extends BaseMapper<PayException> {
    }

    public interface CheckoutSessionMapper extends BaseMapper<PayCheckoutSession> {
    }
}

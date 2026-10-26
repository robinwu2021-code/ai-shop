package ai.neargo.shop.pay.mapper.checkout;

import ai.neargo.shop.pay.checkout.entity.PayCheckoutSession;
import ai.neargo.shop.pay.checkout.entity.PayQrCode;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 收银台的 mapper（db/pay V11 · TDD-支付服务-I5接入层 S1） */
public final class CheckoutMappers {

    private CheckoutMappers() {
    }

    public interface PayQrCodeMapper extends BaseMapper<PayQrCode> {

        /** 单码当日累计（静态码限额）：未关的单都算 —— 待支付的也占额度，免得顾客并发建一堆单绕过上限 */
        @Select("SELECT COALESCE(SUM(amount_minor), 0) FROM pay_order WHERE biz_type = #{bizType} AND biz_no = #{qrCode}"
                + " AND created_at >= #{from} AND status <> 'CLOSED' AND deleted = 0")
        long dailyTotal(@Param("bizType") String bizType, @Param("qrCode") String qrCode, @Param("from") LocalDateTime from);
    }

    public interface PayCheckoutSessionMapper extends BaseMapper<PayCheckoutSession> {

        /** 关单 / 退款即作废这张单的全部令牌（安全规范 §2.5：所以不用 JWT） */
        @Update("UPDATE pay_checkout_session SET status = 'REVOKED', version = version + 1 WHERE pay_order_id = #{payOrderId}"
                + " AND status IN ('OPEN','PAYING') AND deleted = 0")
        int revokeAll(@Param("payOrderId") Long payOrderId);
    }
}

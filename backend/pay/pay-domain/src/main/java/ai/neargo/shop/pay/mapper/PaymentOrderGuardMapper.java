package ai.neargo.shop.pay.mapper;

import ai.neargo.shop.pay.entity.StlPaymentOrderGuard;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Global order identity matches the existing payment owner, independent of audit tenant fields. */
public interface PaymentOrderGuardMapper extends BaseMapper<StlPaymentOrderGuard> {
    /** Create or contend on the same durable order row in the caller's pay transaction. */
    @Insert(
            "INSERT INTO stl_payment_order_guard(order_no,created_at,updated_at) "
                    + "VALUES(#{order},CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)) "
                    + "ON DUPLICATE KEY UPDATE order_no=order_no")
    int ensure(@Param("order") String orderNo);

    /** Lock before any payment operation or PAY row for this order. */
    @Select("SELECT * FROM stl_payment_order_guard WHERE order_no=#{order} FOR UPDATE")
    StlPaymentOrderGuard lock(@Param("order") String orderNo);
}

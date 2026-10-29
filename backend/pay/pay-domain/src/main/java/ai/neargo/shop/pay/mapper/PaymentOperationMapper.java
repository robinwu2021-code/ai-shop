package ai.neargo.shop.pay.mapper;

import ai.neargo.shop.pay.entity.StlPaymentOperation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** The public internal lookup key is globally unique, not a partially scoped tenant lookup. */
public interface PaymentOperationMapper extends BaseMapper<StlPaymentOperation> {
    /** Read a permanent original operation. */
    @Select("SELECT * FROM stl_payment_operation WHERE operation_no=#{no} AND deleted=0")
    StlPaymentOperation find(@Param("no") String operationNo);

    /** The order guard must be acquired before this operation row lock. */
    @Select("SELECT * FROM stl_payment_operation WHERE operation_no=#{no} AND deleted=0 FOR UPDATE")
    StlPaymentOperation lock(@Param("no") String operationNo);

    /** Existing old close/reconciliation paths inspect the canonical send evidence. */
    @Select(
            "SELECT o.* FROM stl_payment_operation o JOIN stl_payment p ON"
                    + " o.operation_no=p.initiating_operation_no WHERE p.out_trade_no=#{no} AND"
                    + " p.direction='PAY'")
    StlPaymentOperation canonicalForPayment(@Param("no") String outTradeNo);
}

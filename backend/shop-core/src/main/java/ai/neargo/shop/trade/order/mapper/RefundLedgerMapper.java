package ai.neargo.shop.trade.order.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Map;

/** 退款账本的条件增量（集群规则 R2）：超上限写 0 行。不动 version，与状态 CAS 互不干扰。 */
@Mapper
public interface RefundLedgerMapper {

    @Update("UPDATE ord_item SET refunded_qty = refunded_qty + #{qty}, refunded_minor = refunded_minor + #{minor} "
            + "WHERE id = #{id} AND refunded_qty + #{qty} <= qty")
    int addItemRefund(@Param("id") long id, @Param("qty") int qty, @Param("minor") long minor);

    @Update("UPDATE ord_sub_order SET refunded_minor = refunded_minor + #{minor} "
            + "WHERE sub_order_no = #{no} AND refunded_minor + #{minor} <= pay_amount")
    int addSubRefund(@Param("no") String no, @Param("minor") long minor);

    @Select("SELECT refunded_minor AS refunded, pay_amount AS pay FROM ord_sub_order WHERE sub_order_no = #{no} LIMIT 1")
    Map<String, Object> subRefundedAndPay(@Param("no") String no);

    @Update("UPDATE ord_sub_order SET refund_progress = #{p} WHERE sub_order_no = #{no}")
    int setProgress(@Param("no") String no, @Param("p") String progress);
}

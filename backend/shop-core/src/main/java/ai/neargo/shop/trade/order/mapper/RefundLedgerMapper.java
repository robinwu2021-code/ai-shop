package ai.neargo.shop.trade.order.mapper;

import ai.neargo.shop.trade.order.entity.OrdRefundExecution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Map;

/** 退款账本的条件增量（集群规则 R2）：超上限写 0 行。不动 version，与状态 CAS 互不干扰。 */
@Mapper
public interface RefundLedgerMapper {

    @Update("UPDATE ord_item SET refunded_qty = refunded_qty + #{qty}, refunded_minor = refunded_minor + #{minor} "
            + "WHERE id = #{id} AND refunded_qty + #{qty} <= qty AND #{qty}>=0 AND #{minor}>=0 "
            + "AND NOT EXISTS (SELECT 1 FROM ord_points_refund_execution e WHERE e.sub_order_no=ord_item.sub_order_no"
            + " AND e.state NOT IN ('COMMITTED','ABORTED') AND e.deleted=0)")
    int addItemRefund(@Param("id") long id, @Param("qty") int qty, @Param("minor") long minor);

    @Update("UPDATE ord_sub_order SET refunded_minor = refunded_minor + #{minor} "
            + "WHERE sub_order_no = #{no} AND refunded_minor + #{minor} <= pay_amount AND #{minor}>=0 "
            + "AND NOT EXISTS (SELECT 1 FROM ord_points_refund_execution e WHERE e.sub_order_no=ord_sub_order.sub_order_no"
            + " AND e.state NOT IN ('COMMITTED','ABORTED') AND e.deleted=0)")
    int addSubRefund(@Param("no") String no, @Param("minor") long minor);

    @Update("UPDATE ord_item SET refunded_qty=refunded_qty+#{qty},refunded_minor=refunded_minor+#{minor}"
            + " WHERE id=#{id} AND refunded_qty+#{qty}<=qty AND #{qty}>=0 AND #{minor}>=0"
            + " AND EXISTS (SELECT 1 FROM ord_points_refund_execution e WHERE e.after_sale_no=#{as}"
            + " AND e.sub_order_no=ord_item.sub_order_no AND e.state='" + OrdRefundExecution.CASH_CONFIRMED + "' AND e.deleted=0)")
    int addPreparedItem(@Param("as") String as,@Param("id") long id,@Param("qty") int qty,@Param("minor") long minor);

    @Update("UPDATE ord_sub_order SET refunded_minor=refunded_minor+#{minor} WHERE sub_order_no=#{no}"
            + " AND refunded_minor+#{minor}<=pay_amount AND #{minor}>=0"
            + " AND EXISTS (SELECT 1 FROM ord_points_refund_execution e WHERE e.after_sale_no=#{as}"
            + " AND e.sub_order_no=ord_sub_order.sub_order_no AND e.state='" + OrdRefundExecution.CASH_CONFIRMED + "' AND e.deleted=0)")
    int addPreparedSub(@Param("as") String as,@Param("no") String no,@Param("minor") long minor);

    /** Called under the order-line lock retained by the ledger update. Cumulative floors preserve minor-unit tails. */
    @Update("""
        UPDATE ord_after_sale_item SET coupon_return_minor=(
            SELECT FLOOR(CAST(i.coupon_share_minor AS DECIMAL(55,15))*i.refunded_qty/i.qty)
                 - FLOOR(CAST(i.coupon_share_minor AS DECIMAL(55,15))*(i.refunded_qty-#{qty})/i.qty)
            FROM ord_item i WHERE i.id=#{item} AND i.sub_order_no=#{sub} AND i.qty>0 AND i.deleted=0)
        WHERE after_sale_no=#{sale} AND sub_order_no=#{sub} AND item_id=#{item}
          AND qty=#{qty} AND deleted=0 AND coupon_return_minor IS NULL
        """)
    int recordCouponReturn(@Param("sale") String sale, @Param("sub") String sub,
                           @Param("item") long item, @Param("qty") int qty);

    @Select("SELECT refunded_minor AS refunded, pay_amount AS pay FROM ord_sub_order WHERE sub_order_no = #{no} LIMIT 1")
    Map<String, Object> subRefundedAndPay(@Param("no") String no);

    @Update("UPDATE ord_sub_order SET refund_progress = #{p} WHERE sub_order_no = #{no}")
    int setProgress(@Param("no") String no, @Param("p") String progress);
}

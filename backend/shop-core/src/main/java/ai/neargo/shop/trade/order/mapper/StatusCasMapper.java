package ai.neargo.shop.trade.order.mapper;

import ai.neargo.shop.trade.entity.OrdSubOrder;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 四个聚合的「读状态 / 条件写状态」。每张表各写一对显式 SQL，不拼表名。
 *
 * <p>条件写同时刷新 {@code updated_at}（售后超时按它计时）与 {@code version}（与乐观锁的老写法互斥）。
 */
@Mapper
public interface StatusCasMapper {

    /** 单笔完成先锁当前事实，售后占位与同订单其他写入不能穿过该串行点。 */
    @Select("SELECT * FROM ord_sub_order WHERE sub_order_no = #{no} AND deleted = 0 FOR UPDATE")
    OrdSubOrder lockOrder(@Param("no") String no);

    @Update(
            "UPDATE ord_sub_order SET fulfillment = #{target}, updated_at = CURRENT_TIMESTAMP,"
                + " version = version + 1 WHERE sub_order_no = #{no} AND deleted = 0 AND"
                + " admission_kind='LEGACY_FUNDED' AND version = #{expectedVersion} AND status ="
                + " 'WAIT_FULFILL' AND open_after_sale_no IS NULL AND (express_no IS NULL OR"
                + " express_no = '')")
    int changeFulfillment(
            @Param("no") String no,
            @Param("target") String target,
            @Param("expectedVersion") long expectedVersion);

    @Select("SELECT open_after_sale_no FROM ord_sub_order WHERE sub_order_no = #{no}")
    String openAfterSaleNo(@Param("no") String no);

    /**
     * 子单上还没收口的售后行数。与 {@link #openAfterSaleNo} 一起用：占位是新写法，
     * 占位上线之前开出的售后只有行没有占位，只看占位会把它们当成「没有售后」。
     */
    @Select("SELECT COUNT(*) FROM ord_after_sale WHERE sub_order_no = #{no} AND deleted = 0 "
            + "AND status NOT IN ('REFUNDED', 'REJECTED', 'CLOSED')")
    long countOpenAfterSales(@Param("no") String no);

    /** 备货是原地事实：条件成功的一方才有资格记事件，不忽略0行。 */
    @Update(
            "UPDATE ord_sub_order SET prepared_at = #{at}, updated_at = CURRENT_TIMESTAMP, version"
                + " = version + 1 WHERE sub_order_no = #{no} AND deleted = 0 AND status ="
                + " 'WAIT_FULFILL' AND admission_kind='LEGACY_FUNDED' AND prepared_at IS NULL AND"
                + " open_after_sale_no IS NULL")
    int prepareIfEligible(@Param("no") String no, @Param("at") long preparedAt);

    @Select("SELECT status FROM ord_order WHERE order_no = #{no} LIMIT 1")
    String paymentStatus(@Param("no") String no);

    @Update(
            "UPDATE ord_order SET status = #{to}, updated_at = #{now}, version = version + 1 WHERE"
                + " order_no = #{no} AND status = #{from} AND (admission_kind='LEGACY_FUNDED' OR"
                + " (#{to}='CANCELLED' AND status='WAIT_OFFLINE_PAY' AND receipt_provenance IS"
                + " NULL))")
    int casPayment(
            @Param("no") String no,
            @Param("from") String from,
            @Param("to") String to,
            @Param("now") LocalDateTime now);

    @Select(
            "SELECT fulfillment_authority FROM ord_sub_order WHERE sub_order_no = #{no} "
                    + "AND deleted = 0 FOR UPDATE")
    String fulfillmentAuthority(@Param("no") String no);

    @Select("SELECT status FROM ord_sub_order WHERE sub_order_no = #{no} LIMIT 1")
    String orderStatus(@Param("no") String no);

    @Update(
            "UPDATE ord_sub_order SET status = #{to}, updated_at = #{now}, version = version + 1"
                + " WHERE sub_order_no = #{no} AND status = #{from} AND deleted = 0 AND"
                + " (admission_kind='LEGACY_FUNDED' OR (#{to}='CANCELLED' AND status='WAIT_PAY'))"
                + " AND (#{to} <> 'COMPLETED' OR fulfillment_authority IS NULL OR"
                + " fulfillment_authority <> 'SERVICE_EXECUTION')")
    int casOrder(
            @Param("no") String no,
            @Param("from") String from,
            @Param("to") String to,
            @Param("now") LocalDateTime now);

    /** 子单完成时刻（售后期起算点）：只写第一次，不动 version（与 CAS 互不干扰） */
    @Update(
            "UPDATE ord_sub_order SET completed_at = #{at} WHERE sub_order_no = #{no} "
                    + "AND admission_kind='LEGACY_FUNDED' AND completed_at IS NULL")
    int markOrderCompleted(@Param("no") String no, @Param("at") long at);

    @Select("SELECT status FROM ord_shipment WHERE shipment_no = #{no} LIMIT 1")
    String shipmentStatus(@Param("no") String no);

    @Update("UPDATE ord_shipment SET status = #{to}, updated_at = #{now}, version = version + 1 "
            + "WHERE shipment_no = #{no} AND status = #{from}")
    int casShipment(@Param("no") String no, @Param("from") String from, @Param("to") String to,
                    @Param("now") LocalDateTime now);

    @Select("SELECT status FROM ord_after_sale WHERE after_sale_no = #{no} LIMIT 1")
    String afterSaleStatus(@Param("no") String no);

    @Update("UPDATE ord_after_sale SET status = #{to}, updated_at = #{now}, version = version + 1 "
            + "WHERE after_sale_no = #{no} AND status = #{from}")
    int casAfterSale(@Param("no") String no, @Param("from") String from, @Param("to") String to,
                     @Param("now") LocalDateTime now);
}

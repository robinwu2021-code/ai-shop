package ai.neargo.shop.pay.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Map;

/** Durable pre-split hold; bill-row locks serialize hold creation with split preparation. */
public interface PointsSettlementRefundHoldMapper {
    @Select("SELECT * FROM stl_points_refund_hold WHERE after_sale_no=#{no}")
    Map<String, Object> find(String no);

    @Select(
            "SELECT COUNT(*) FROM stl_points_refund_hold WHERE settle_no=#{settle} AND"
                + " status='PENDING'")
    long pendingForBill(String settle);

    @Insert(
            "INSERT INTO"
                + " stl_points_refund_hold(after_sale_no,settle_no,sub_order_no,goods_minor,freight_minor,goods_paid_minor,status)"
                + " VALUES(#{no},#{settle},#{sub},#{goods},#{freight},#{paid},'PENDING')")
    int create(
            @Param("no") String no,
            @Param("settle") String settle,
            @Param("sub") String sub,
            @Param("goods") long goods,
            @Param("freight") long freight,
            @Param("paid") long paid);

    @Update(
            "UPDATE stl_points_refund_hold SET"
                + " status=#{next},proof_no=#{proof},updated_at=CURRENT_TIMESTAMP WHERE"
                + " after_sale_no=#{no} AND status='PENDING'")
    int finish(@Param("no") String no, @Param("next") String next, @Param("proof") String proof);
}

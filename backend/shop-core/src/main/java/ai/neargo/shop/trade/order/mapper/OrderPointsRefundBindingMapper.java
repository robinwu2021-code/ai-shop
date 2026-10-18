package ai.neargo.shop.trade.order.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface OrderPointsRefundBindingMapper {
    @Select("SELECT * FROM ord_points_refund_binding WHERE after_sale_no=#{no}")
    Map<String, Object> find(String no);

    @Select("SELECT * FROM ord_points_refund_binding WHERE after_sale_no=#{no} FOR UPDATE")
    Map<String, Object> lock(String no);

    @Select("SELECT * FROM ord_points_refund_binding WHERE sub_order_no=#{sub} ORDER BY after_sale_no")
    List<Map<String, Object>> forSubOrder(String sub);

    @Insert(
            "INSERT INTO"
                + " ord_points_refund_binding(after_sale_no,sub_order_no,phase,source_version,command_json)"
                + " VALUES(#{no},#{sub},'STAGED',#{sourceVersion},#{body})")
    int create(
            @Param("no") String no,
            @Param("sub") String sub,
            @Param("sourceVersion") long sourceVersion,
            @Param("body") String body);

    @Update(
            "UPDATE ord_points_refund_binding SET phase=#{next},preparation_json=#{preparation},"
                    + " cash_payment_no=#{cash},version=version+1,updated_at=CURRENT_TIMESTAMP"
                    + " WHERE after_sale_no=#{no} AND phase=#{from} AND version=#{version}")
    int change(
            @Param("no") String no,
            @Param("version") long version,
            @Param("from") String from,
            @Param("next") String next,
            @Param("preparation") String preparation,
            @Param("cash") String cash);

    @Update("UPDATE ord_points_refund_binding SET phase='POINTS_CONFIRMED',outcome_json=#{outcome},"
            + "version=version+1,updated_at=CURRENT_TIMESTAMP WHERE after_sale_no=#{no}"
            + " AND phase=#{from} AND version=#{version}")
    int confirm(@Param("no") String no, @Param("version") long version,
            @Param("from") String from, @Param("outcome") String outcome);

    @Select(
            "SELECT after_sale_no FROM ord_points_refund_binding WHERE phase NOT IN"
                + " ('COMMITTED','ABORTED') ORDER BY updated_at,after_sale_no LIMIT #{limit}")
    List<String> pending(int limit);
}

package ai.neargo.shop.trade.order.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface OrderPointsPlacementMapper {
    @Select("SELECT payload_json FROM ord_points_placement WHERE order_no=#{order}")
    String find(String order);

    @Select("SELECT payload_json FROM ord_points_placement WHERE order_no=#{order} FOR UPDATE")
    String lock(String order);

    @Insert(
            "INSERT INTO ord_points_placement(order_no,phase,payload_json,version)"
                + " VALUES(#{order},'RAW',#{body},0)")
    int create(@Param("order") String order, @Param("body") String body);

    @Update(
            "UPDATE ord_points_placement SET"
                + " phase=#{phase},payload_json=#{body},version=version+1,updated_at=CURRENT_TIMESTAMP"
                + " WHERE order_no=#{order} AND version=#{version}")
    int change(
            @Param("order") String order,
            @Param("version") long version,
            @Param("phase") String phase,
            @Param("body") String body);

    @Select(
            "SELECT order_no FROM ord_points_placement WHERE phase NOT IN ('READY','CANCELLED')"
                + " ORDER BY updated_at,order_no LIMIT #{limit}")
    List<String> pending(int limit);
}

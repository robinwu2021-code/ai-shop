package ai.neargo.shop.trade.order.mapper;

import org.apache.ibatis.annotations.*;

@Mapper
public interface OrderPointsRecoveryCursorMapper {
    @Insert(
            "INSERT INTO ord_points_recovery_cursor(queue_name,after_sub_order_no)"
                    + " VALUES('CLOSURE','') ON DUPLICATE KEY UPDATE queue_name=queue_name")
    int initialize();

    @Select(
            "SELECT after_sub_order_no FROM ord_points_recovery_cursor"
                    + " WHERE queue_name='CLOSURE' FOR UPDATE")
    String lock();

    @Update(
            "UPDATE ord_points_recovery_cursor SET after_sub_order_no=#{after},"
                    + " updated_at=CURRENT_TIMESTAMP WHERE queue_name='CLOSURE'")
    int advance(String after);
}

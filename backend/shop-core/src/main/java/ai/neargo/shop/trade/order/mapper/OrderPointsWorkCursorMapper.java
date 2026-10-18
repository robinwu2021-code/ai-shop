package ai.neargo.shop.trade.order.mapper;

import org.apache.ibatis.annotations.*;

@Mapper
public interface OrderPointsWorkCursorMapper {
    @Insert("INSERT INTO ord_points_work_cursor(queue_name,after_reference) VALUES(#{queue},'') ON DUPLICATE KEY UPDATE queue_name=queue_name")
    int initialize(String queue);
    @Select("SELECT after_reference FROM ord_points_work_cursor WHERE queue_name=#{queue} FOR UPDATE")
    String lock(String queue);
    @Update("UPDATE ord_points_work_cursor SET after_reference=#{after},updated_at=CURRENT_TIMESTAMP WHERE queue_name=#{queue}")
    int advance(@Param("queue") String queue,@Param("after") String after);
}

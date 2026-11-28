package ai.neargo.shop.trade.order.mapper;

import ai.neargo.shop.trade.entity.OrdItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 订单域持久化：沿用原表、平台工厂及SQL语义。 */
public interface OrderItemMapper extends BaseMapper<OrdItem> {

    /** 退款要保留旧库特有的券证据。读取整行也允许定稿库缺少旧字段；缺失仍为未知。 */
    @Select("""
            <script>
            SELECT * FROM ord_item WHERE sub_order_no = #{subOrderNo} AND deleted = 0 ORDER BY id
            <if test="lock">FOR UPDATE</if>
            </script>
            """)
    List<OrdItem> refundItems(@Param("subOrderNo") String subOrderNo, @Param("lock") boolean lock);
}

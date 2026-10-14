package ai.neargo.shop.trade.order.mapper;

import ai.neargo.shop.trade.order.entity.OrdCreateIntent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Current reads serialize creation with recovery, including under repeatable-read isolation. */
public interface CreateIntentMapper extends BaseMapper<OrdCreateIntent> {
    @Select("SELECT * FROM ord_create_intent WHERE pay_no=#{no} AND deleted=0 FOR UPDATE")
    OrdCreateIntent lock(@Param("no") String payNo);
}

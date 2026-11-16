package ai.neargo.shop.trade.order.mapper;

import ai.neargo.shop.trade.entity.OrdAfterSale;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 订单域持久化：沿用原表、平台工厂及SQL语义。 */
public interface AfterSaleMapper extends BaseMapper<OrdAfterSale> {

    /** After-sales cases of one store that are not finished (refunded, rejected or closed). */
    @Select("SELECT COUNT(*) FROM ord_after_sale a JOIN ord_sub_order s ON s.sub_order_no = a.sub_order_no"
            + " WHERE s.store_no = #{storeNo} AND a.deleted = 0"
            + " AND a.status NOT IN ('REFUNDED','REJECTED','CLOSED')")
    long countOpenByStore(@Param("storeNo") String storeNo);

}

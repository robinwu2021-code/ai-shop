package ai.neargo.shop.trade.order.mapper;

import ai.neargo.shop.trade.order.entity.OrdAfterSaleItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 售后行 mapper（V404）。 */
@Mapper
public interface AfterSaleItemMapper extends BaseMapper<OrdAfterSaleItem> {
}

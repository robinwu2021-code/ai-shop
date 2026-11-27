package ai.neargo.shop.trade.order.mapper;

import ai.neargo.shop.common.config.TradeTableMapper;
import ai.neargo.shop.trade.order.entity.OrdAfterSaleItem;
import org.apache.ibatis.annotations.Mapper;

/** 售后行 mapper（V404）。 */
@Mapper
public interface AfterSaleItemMapper extends TradeTableMapper<OrdAfterSaleItem> {
}

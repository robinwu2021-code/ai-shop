package ai.neargo.shop.trade.order.mapper;

import ai.neargo.shop.common.config.TradeTableMapper;
import ai.neargo.shop.trade.entity.OrdOrder;

/** 订单域持久化：沿用原表、平台工厂及SQL语义。 */
public interface PaymentMapper extends TradeTableMapper<OrdOrder> {

}

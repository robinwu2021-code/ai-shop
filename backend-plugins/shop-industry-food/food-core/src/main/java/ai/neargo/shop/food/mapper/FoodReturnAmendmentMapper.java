package ai.neargo.shop.food.mapper;

import ai.neargo.shop.common.config.TradeTableMapper;
import ai.neargo.shop.food.entity.FnbReturnAmendment;

/** 退菜改单属于订单域（门店交易库，并入售后）：挂交易句柄。 */
public interface FoodReturnAmendmentMapper extends TradeTableMapper<FnbReturnAmendment> {}

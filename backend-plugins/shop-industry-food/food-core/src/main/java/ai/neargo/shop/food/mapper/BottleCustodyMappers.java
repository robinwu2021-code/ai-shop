package ai.neargo.shop.food.mapper;

import ai.neargo.shop.common.config.TradeTableMapper;
import ai.neargo.shop.food.entity.FnbBottleCustody;
import ai.neargo.shop.food.entity.FnbBottleMovement;


/** All queries retain explicit original entity/store scope. */
public final class BottleCustodyMappers {
    private BottleCustodyMappers() {}

    public interface CustodyMapper extends TradeTableMapper<FnbBottleCustody> {}

    public interface MovementMapper extends TradeTableMapper<FnbBottleMovement> {}
}

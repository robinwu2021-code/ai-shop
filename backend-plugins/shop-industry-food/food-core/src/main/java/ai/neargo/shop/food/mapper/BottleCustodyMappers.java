package ai.neargo.shop.food.mapper;

import ai.neargo.shop.food.entity.FnbBottleCustody;
import ai.neargo.shop.food.entity.FnbBottleMovement;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** All queries retain explicit original entity/store scope. */
public final class BottleCustodyMappers {
    private BottleCustodyMappers() {}

    public interface CustodyMapper extends BaseMapper<FnbBottleCustody> {}

    public interface MovementMapper extends BaseMapper<FnbBottleMovement> {}
}

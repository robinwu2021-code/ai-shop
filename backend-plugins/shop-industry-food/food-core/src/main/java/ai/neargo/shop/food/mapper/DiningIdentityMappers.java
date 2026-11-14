package ai.neargo.shop.food.mapper;

import ai.neargo.shop.food.entity.FnbDiningDevice;
import ai.neargo.shop.food.entity.FnbIdentityAuthorization;
import ai.neargo.shop.food.entity.FnbIdentityOperation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** Typed primary-store mappers discovered by the existing FOOD BaseMapper scan. */
public final class DiningIdentityMappers {
    private DiningIdentityMappers() {}

    public interface DeviceMapper extends BaseMapper<FnbDiningDevice> {}

    public interface AuthorizationMapper extends BaseMapper<FnbIdentityAuthorization> {}

    public interface OperationMapper extends BaseMapper<FnbIdentityOperation> {}
}

package ai.neargo.shop.food.mapper;

import ai.neargo.shop.food.entity.FnbChannelImportGuard;
import ai.neargo.shop.food.entity.FnbChannelOrderSource;
import ai.neargo.shop.food.entity.FnbChannelParty;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * Native restaurant source persistence uses the existing FOOD primary mapper scan and transaction
 * manager.
 */
public final class DiningChannelMappers {
    private DiningChannelMappers() {}

    public interface ChannelImportGuardMapper extends BaseMapper<FnbChannelImportGuard> {}

    public interface ChannelOrderSourceMapper extends BaseMapper<FnbChannelOrderSource> {}

    public interface ChannelPartyMapper extends BaseMapper<FnbChannelParty> {}
}

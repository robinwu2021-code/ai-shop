package ai.neargo.shop.food.mapper;

import ai.neargo.shop.common.config.TradeTableMapper;
import ai.neargo.shop.food.entity.FnbChannelImportGuard;
import ai.neargo.shop.food.entity.FnbChannelOrderSource;
import ai.neargo.shop.food.entity.FnbChannelParty;


/**
 * Native restaurant source persistence uses the existing FOOD primary mapper scan and transaction
 * manager.
 */
public final class DiningChannelMappers {
    private DiningChannelMappers() {}

    public interface ChannelImportGuardMapper extends TradeTableMapper<FnbChannelImportGuard> {}

    public interface ChannelOrderSourceMapper extends TradeTableMapper<FnbChannelOrderSource> {}

    public interface ChannelPartyMapper extends TradeTableMapper<FnbChannelParty> {}
}

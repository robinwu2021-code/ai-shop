package ai.neargo.shop.retail.commerce.mapper;

import ai.neargo.shop.common.config.TradeTableMapper;
import ai.neargo.shop.retail.commerce.entity.RetailChannelImportGuard;
import ai.neargo.shop.retail.commerce.entity.RetailChannelOrderSource;
import ai.neargo.shop.retail.commerce.entity.RetailChannelParty;

/** Retail channel source tables live in the store trade database (ADR-068): bound to the trade module handle. */
public final class RetailChannelMappers {
    private RetailChannelMappers() {}

    public interface ImportGuardMapper extends TradeTableMapper<RetailChannelImportGuard> {}

    public interface OrderSourceMapper extends TradeTableMapper<RetailChannelOrderSource> {}

    public interface PartyMapper extends TradeTableMapper<RetailChannelParty> {}
}

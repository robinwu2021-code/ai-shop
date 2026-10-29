package ai.neargo.shop.retail.commerce.mapper;

import ai.neargo.shop.retail.commerce.entity.RetailChannelImportGuard;
import ai.neargo.shop.retail.commerce.entity.RetailChannelOrderSource;
import ai.neargo.shop.retail.commerce.entity.RetailChannelParty;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** Retail channel source tables remain on the original primary persistence boundary. */
public final class RetailChannelMappers {
    private RetailChannelMappers() {}

    public interface ImportGuardMapper extends BaseMapper<RetailChannelImportGuard> {}

    public interface OrderSourceMapper extends BaseMapper<RetailChannelOrderSource> {}

    public interface PartyMapper extends BaseMapper<RetailChannelParty> {}
}

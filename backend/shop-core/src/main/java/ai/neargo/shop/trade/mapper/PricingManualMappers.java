package ai.neargo.shop.trade.mapper;

import ai.neargo.shop.trade.entity.OrdManualPriceAdjustment;
import ai.neargo.shop.trade.entity.OrdManualPricePolicy;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** Real concession policy and approval persistence, separate from immutable economic quotes. */
public final class PricingManualMappers {
    private PricingManualMappers() { }
    @Mapper public interface PolicyMapper extends BaseMapper<OrdManualPricePolicy> { }
    @Mapper public interface AdjustmentMapper extends BaseMapper<OrdManualPriceAdjustment> { }
}

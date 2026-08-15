package ai.neargo.shop.marketing.attribution.mapper;

import ai.neargo.shop.marketing.attribution.entity.MktAttribution;
import ai.neargo.shop.marketing.attribution.entity.MktAttributionLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 归因域的 Mapper 集合。 */
public final class AttributionMappers {

    private AttributionMappers() {
    }

    public interface AttributionMapper extends BaseMapper<MktAttribution> {
    }

    public interface AttributionLogMapper extends BaseMapper<MktAttributionLog> {
    }

    public interface AttributionRuleMapper
            extends BaseMapper<ai.neargo.shop.marketing.attribution.entity.MktAttributionRule> {
    }

    public interface FissionCampaignMapper
            extends BaseMapper<ai.neargo.shop.marketing.attribution.entity.MktFissionCampaign> {
    }
}

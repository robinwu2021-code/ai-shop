package ai.neargo.shop.risk.mapper;

import ai.neargo.shop.risk.entity.RiskBlacklist;
import ai.neargo.shop.risk.entity.RiskEvent;
import ai.neargo.shop.risk.entity.RiskRule;
import ai.neargo.shop.risk.entity.RiskSignalHit;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 风控域的 Mapper 集合。 */
public final class RiskMappers {

    private RiskMappers() {
    }

    public interface RiskEventMapper extends BaseMapper<RiskEvent> {
    }

    public interface RiskSignalHitMapper extends BaseMapper<RiskSignalHit> {
    }

    public interface RiskBlacklistMapper extends BaseMapper<RiskBlacklist> {
    }

    public interface RiskRuleMapper extends BaseMapper<RiskRule> {
    }
}

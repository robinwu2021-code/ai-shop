package ai.neargo.shop.community.mapper;

import ai.neargo.shop.community.entity.CmtCommunity;
import ai.neargo.shop.community.entity.CmtPickupPoint;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * community 域的 Mapper 集合。
 *
 * <p>社区与自提点是**主数据**：谁都要读，只有运营能写。放在 user 域里的时候，
 * 「读社区」和「读用户」共用一个入口，看不出这个区别。
 */
public final class CommunityMappers {

    private CommunityMappers() {
    }

    public interface CommunityMapper extends BaseMapper<CmtCommunity> {
    }

    public interface PickupPointMapper extends BaseMapper<CmtPickupPoint> {
    }

    public interface CommunityApplyMapper
            extends BaseMapper<ai.neargo.shop.community.entity.CmtCommunityApply> {
    }
}

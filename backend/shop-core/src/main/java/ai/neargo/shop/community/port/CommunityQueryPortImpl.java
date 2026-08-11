package ai.neargo.shop.community.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.user.CommunityQueryPort;
import ai.neargo.shop.community.entity.CmtCommunity;
import ai.neargo.shop.community.mapper.CommunityMappers.CommunityMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * {@link CommunityQueryPort} 实现。
 *
 * <p>两个方法都解除数据域：调用方是商家域在判断自己的经营范围与积分开关，
 * 而社区表本身不按商家隔离。不解除的话，返回的永远是空——且不报错。
 */
@Component
public class CommunityQueryPortImpl implements CommunityQueryPort {

    private static final String OPEN = "OPEN";

    private final CommunityMapper communityMapper;

    public CommunityQueryPortImpl(CommunityMapper communityMapper) {
        this.communityMapper = communityMapper;
    }

    @Override
    public List<String> openCommunityNos() {
        return DataScopeContext.executeWithoutScope(() ->
                        communityMapper.selectList(Wrappers.<CmtCommunity>lambdaQuery()
                                .eq(CmtCommunity::getStatus, OPEN)))
                .stream().map(CmtCommunity::getCommunityNo).toList();
    }

    @Override
    public boolean anyPointsEnabled(Collection<String> communityNos) {
        if (communityNos == null || communityNos.isEmpty()) {
            return false;
        }
        return DataScopeContext.executeWithoutScope(() ->
                        communityMapper.selectList(Wrappers.<CmtCommunity>lambdaQuery()
                                .in(CmtCommunity::getCommunityNo, communityNos)))
                .stream().anyMatch(c -> !Boolean.FALSE.equals(c.getPointsEnabled()));
    }

    @Override
    public java.util.List<String> openCommunityNosUnderRegion(String regionPrefix) {
        if (regionPrefix == null || regionPrefix.isBlank()) {
            // 空前缀会匹配一切 —— 那意味着「框了个空区划」的商家突然覆盖全平台
            return java.util.List.of();
        }
        return DataScopeContext.executeWithoutScope(() ->
                        communityMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                                .<ai.neargo.shop.community.entity.CmtCommunity>lambdaQuery()
                                .eq(ai.neargo.shop.community.entity.CmtCommunity::getStatus, "OPEN")
                                .likeRight(ai.neargo.shop.community.entity.CmtCommunity::getRegionCode,
                                        regionPrefix)))
                .stream().map(ai.neargo.shop.community.entity.CmtCommunity::getCommunityNo).toList();
    }

    @Override
    public String communityName(String communityNo) {
        if (communityNo == null || communityNo.isBlank()) {
            return communityNo;
        }
        var row = DataScopeContext.executeWithoutScope(() ->
                communityMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.community.entity.CmtCommunity>lambdaQuery()
                        .eq(ai.neargo.shop.community.entity.CmtCommunity::getCommunityNo, communityNo)
                        .last("LIMIT 1")));
        return row == null || row.getName() == null ? communityNo : row.getName();
    }
}

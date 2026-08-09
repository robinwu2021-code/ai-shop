package ai.neargo.shop.user.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.user.CommunityQueryPort;
import ai.neargo.shop.user.community.entity.CmtCommunity;
import ai.neargo.shop.user.mapper.UserMappers.CommunityMapper;
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
}

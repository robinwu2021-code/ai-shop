package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchEntityCommunity;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityCommunityMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 「这个主体能不能卖到某个社区」—— 三档服务范围的<b>唯一一份</b>判定。
 *
 * <ul>
 *   <li>{@code PLATFORM} / {@code CITY}：恒可达（城市档的城市过滤在别处做）；</li>
 *   <li>{@code COMMUNITY}：必须在 {@code mch_entity_community} 里登记过这个社区。</li>
 * </ul>
 *
 * <p>原来是 {@code MerchantServiceImpl} 的私有方法。门店化之后「附近的门店」
 * （{@code StoreDirectoryPortImpl}）要用同一条规则 —— 各写一份的下场是店铺列表与商家列表
 * 对「谁能卖到这里」给出两个答案，而两边都不报错。所以抽出来，两处共用。
 */
@Component
public class EntityReachability {

    private final MchEntityCommunityMapper entityCommunityMapper;

    public EntityReachability(MchEntityCommunityMapper entityCommunityMapper) {
        this.entityCommunityMapper = entityCommunityMapper;
    }

    /** 给主体查询加上「能卖到 communityNo」的条件。{@code communityNo} 为空 = 不过滤 */
    public void apply(LambdaQueryWrapper<MchEntity> w, String communityNo) {
        if (communityNo == null || communityNo.isBlank()) {
            return;
        }
        List<String> reach = DataScopeContext.executeWithoutScope(() ->
                        entityCommunityMapper.selectList(Wrappers.<MchEntityCommunity>lambdaQuery()
                                .eq(MchEntityCommunity::getCommunityNo, communityNo)))
                .stream().map(MchEntityCommunity::getEntityNo).toList();
        w.and(q -> {
            q.in(MchEntity::getServiceScope, List.of("PLATFORM", "CITY"));
            if (!reach.isEmpty()) {
                q.or(x -> x.eq(MchEntity::getServiceScope, "COMMUNITY")
                        .in(MchEntity::getEntityNo, reach));
            }
        });
    }
}

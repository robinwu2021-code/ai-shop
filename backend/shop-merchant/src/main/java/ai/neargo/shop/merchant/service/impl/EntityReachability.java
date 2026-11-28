package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchServiceArea;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ServiceAreaMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 「能卖到这个小区」的三档判定：服务范围为平台、全市的不看小区；为小区的，看门店的基础覆盖面有没有纳入它。
 *
 * <p>覆盖只在门店上（TDD-平台基础库换表 E：主体层不再有覆盖，原 mch_entity_community 退役）：
 * 主体级列表按「名下任一门店覆盖」列出，门店级列表按这家店自己的行判（{@link #storesCovering}）。
 */
@Component
public class EntityReachability {

    /** 服务范围：平台、全市 —— 不看小区 */
    public static final List<String> WIDE_SCOPES = List.of("PLATFORM", "CITY");

    private final ServiceAreaMapper serviceAreaMapper;

    public EntityReachability(ServiceAreaMapper serviceAreaMapper) {
        this.serviceAreaMapper = serviceAreaMapper;
    }

    /** 给主体查询加上「能卖到 communityNo」的条件。{@code communityNo} 为空 = 不过滤 */
    public void apply(LambdaQueryWrapper<MchEntity> w, String communityNo) {
        if (communityNo == null || communityNo.isBlank()) {
            return;
        }
        List<String> reach = covering(communityNo).stream().map(MchServiceArea::getEntityNo).distinct().toList();
        w.and(q -> {
            q.in(MchEntity::getServiceScope, WIDE_SCOPES);
            if (!reach.isEmpty()) {
                q.or(x -> x.eq(MchEntity::getServiceScope, "COMMUNITY")
                        .in(MchEntity::getEntityNo, reach));
            }
        });
    }

    /** 基础覆盖面纳入了 communityNo 的门店号 */
    public Set<String> storesCovering(String communityNo) {
        return covering(communityNo).stream().map(MchServiceArea::getStoreNo).collect(Collectors.toSet());
    }

    private List<MchServiceArea> covering(String communityNo) {
        return DataScopeContext.executeWithoutScope(() -> serviceAreaMapper.selectList(
                Wrappers.<MchServiceArea>lambdaQuery()
                        .select(MchServiceArea::getEntityNo, MchServiceArea::getStoreNo)
                        .eq(MchServiceArea::getChannel, MchServiceArea.BASE_CHANNEL)
                        .eq(MchServiceArea::getLevel, MchServiceArea.LEVEL_COMMUNITY)
                        .eq(MchServiceArea::getRefCode, communityNo)
                        .eq(MchServiceArea::getMode, MchServiceArea.MODE_INCLUDE)
                        .eq(MchServiceArea::getStatus, MchServiceArea.ACTIVE)
                        .isNotNull(MchServiceArea::getStoreNo)));
    }
}

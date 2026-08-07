package ai.neargo.shop.user.service.impl;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizIdentityResolver;
import ai.neargo.shop.user.entity.CmtPickupPoint;
import ai.neargo.shop.user.entity.UsrMerchant;
import ai.neargo.shop.user.mapper.UserMappers.MerchantMapper;
import ai.neargo.shop.user.mapper.UserMappers.PickupPointMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 「这个用户在经营侧是谁」的真实解析（替代 S0 的 {@link BizIdentityResolver#NONE}）。
 *
 * <p>三个作用域各查各的，**互不推导**：
 * <ul>
 *   <li>{@code merchantNo}：我是哪家店的店主（{@code owner_user_no = 我}）</li>
 *   <li>{@code pickupNos}：我这家店承接了哪些常驻自提点</li>
 *   <li>{@code groupNos}：我发起了哪些团（S5 接 marketing 后填；现在恒空）</li>
 * </ul>
 *
 * <p>刻意不写「是商家就自动拥有自提点权限」这类推导 —— 一家店可以不做自提点，
 * 一个自提点也可能承接别家的货。推导出来的权限是最难审计的权限。
 */
@Component
public class BizIdentityResolverImpl implements BizIdentityResolver {

    private final MerchantMapper merchantMapper;
    private final PickupPointMapper pickupMapper;

    public BizIdentityResolverImpl(MerchantMapper merchantMapper, PickupPointMapper pickupMapper) {
        this.merchantMapper = merchantMapper;
        this.pickupMapper = pickupMapper;
    }

    @Override
    public BizContext resolve(String userNo) {
        UsrMerchant merchant = merchantMapper.selectOne(Wrappers.<UsrMerchant>lambdaQuery()
                .eq(UsrMerchant::getOwnerUserNo, userNo)
                .eq(UsrMerchant::getStatus, "ACTIVE")
                .last("limit 1"));
        if (merchant == null) {
            // 不是商家：空作用域 = 所有 /biz/** 都 403。fail-closed
            return BizContext.NONE;
        }

        Set<String> pickupNos = pickupMapper.selectList(Wrappers.<CmtPickupPoint>lambdaQuery()
                        .eq(CmtPickupPoint::getOwnerRef, merchant.getMerchantNo())
                        .eq(CmtPickupPoint::getType, "STORE")
                        .eq(CmtPickupPoint::getStatus, "ACTIVE")).stream()
                .map(CmtPickupPoint::getPickupNo)
                .collect(Collectors.toSet());

        return new BizContext(merchant.getMerchantNo(), pickupNos, Set.of());
    }
}

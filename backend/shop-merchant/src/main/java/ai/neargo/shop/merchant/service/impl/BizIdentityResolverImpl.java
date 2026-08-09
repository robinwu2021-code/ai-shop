package ai.neargo.shop.merchant.service.impl;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizIdentityResolver;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.entity.MchStoreRole;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreRoleMapper;
import ai.neargo.shop.spi.user.PickupQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 「这个用户在经营侧是谁」的真实解析（替代 S0 的 {@link BizIdentityResolver#NONE}）。
 *
 * <p>三个作用域各查各的，**互不推导**：
 * <ul>
 *   <li>{@code merchantNo}：我参与的主体（{@code mch_account}）。
 *       <b>M1 起身份来源是成员表，不再是 {@code mch_entity.owner_user_no}</b> ——
 *       那一列一个主体只能有一个人，是「一个账号只能是一家店老板」的根源。
 *       多主体切换（App）在 M6 放开，<b>现在仍只解析出一个</b>：取默认主体，
 *       行为与 M1 之前完全一致</li>
 *   <li>{@code pickupNos}：我这家店承接了哪些常驻自提点</li>
 *   <li>{@code groupNos}：我发起了哪些团（S5 接 marketing 后填；现在恒空）</li>
 * </ul>
 *
 * <p>刻意不写「是商家就自动拥有自提点权限」这类推导 —— 一家店可以不做自提点，
 * 一个自提点也可能承接别家的货。推导出来的权限是最难审计的权限。
 */
@Component
public class BizIdentityResolverImpl implements BizIdentityResolver {

    private final MchEntityMapper merchantMapper;
    private final PickupQueryPort pickupQueryPort;
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper staffMapper;
    private final MchStoreMapper storeMapper;
    private final MchStoreRoleMapper roleMapper;

    public BizIdentityResolverImpl(MchEntityMapper merchantMapper, PickupQueryPort pickupQueryPort,
                                   ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper staffMapper,
                                   MchStoreMapper storeMapper, MchStoreRoleMapper roleMapper) {
        this.storeMapper = storeMapper;
        this.roleMapper = roleMapper;
        this.merchantMapper = merchantMapper;
        this.pickupQueryPort = pickupQueryPort;
        this.staffMapper = staffMapper;
    }

    @Override
    public BizContext resolve(String userNo) {
        /*
         * 我参与的主体，默认主体优先。**排序是确定的**（is_primary 倒序 + id 正序）——
         * 不给排序的 limit 1 会在多主体时随机挑一个，而"今天进的是 A 店、明天是 B 店"
         * 这种故障没人能复现。
         */
        var memberships = staffMapper.selectList(
                Wrappers.<ai.neargo.shop.merchant.entity.MchAccount>lambdaQuery()
                        /*
                         * **两条登录路径解析到同一条员工记录**：
                         *   小程序 → C 端账号（user_no）
                         *   App    → 员工独立登录，principal 是 mch_account_no
                         * 只认第一条的话，App 上的店员登录后作用域为空，所有 /biz/** 都 403，
                         * 而他看到的只是「打不开」。
                         */
                        .and(q -> q.eq(ai.neargo.shop.merchant.entity.MchAccount::getUserNo, userNo)
                                .or().eq(ai.neargo.shop.merchant.entity.MchAccount::getMchAccountNo, userNo))
                        .eq(ai.neargo.shop.merchant.entity.MchAccount::getStatus,
                                ai.neargo.shop.merchant.entity.MchAccount.ACTIVE)
                        .orderByDesc(ai.neargo.shop.merchant.entity.MchAccount::getIsPrimary)
                        .orderByAsc(ai.neargo.shop.merchant.entity.MchAccount::getId));
        if (memberships.isEmpty()) {
            // 不是商家：空作用域 = 所有 /biz/** 都 403。fail-closed
            return BizContext.NONE;
        }
        // M1 仍只取一个主体 —— BizContext 扩成多主体是 M6 的事，这一期要零行为变化
        MchEntity merchant = merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                .eq(MchEntity::getEntityNo, memberships.get(0).getEntityNo())
                .eq(MchEntity::getStatus, "ACTIVE").last("limit 1"));
        if (merchant == null) {
            // 成员行在但主体被封 —— 同样 fail-closed
            return BizContext.NONE;
        }

        Set<String> pickupNos = pickupQueryPort.activeStorePickupNos(merchant.getEntityNo()).stream()
                .collect(Collectors.toSet());

        /*
         * 我有权限的门店。**老板与店员不是同一套口径**：
         *   老板（is_owner）→ 主体下**全部**门店，包括他明天新建的那家；
         *   店员          → 只有 mch_store_role 里被授权到的那几家。
         *
         * 给店员按主体展开的话，加一个店员等于把所有门店都交出去 ——
         * 而「A 店店员能看到 B 店订单」这种越权不会报错，只会安静地多看到一些东西。
         */
        var membership = memberships.get(0);
        Set<String> storeNos = Boolean.TRUE.equals(membership.getIsOwner())
                ? storeMapper.selectList(Wrappers.<MchStore>lambdaQuery()
                        .eq(MchStore::getEntityNo, merchant.getEntityNo())
                        .eq(MchStore::getStatus, "ACTIVE")).stream()
                        .map(MchStore::getStoreNo).collect(Collectors.toSet())
                : roleMapper.selectList(Wrappers.<MchStoreRole>lambdaQuery()
                        .eq(MchStoreRole::getMchAccountNo, membership.getMchAccountNo())).stream()
                        .map(MchStoreRole::getStoreNo).collect(Collectors.toSet());

        /*
         * 默认门店：老板取 is_default，店员取他被授权的第一家。
         * 请求带了 X-Store-No 时由 Filter 覆盖 —— 这里只负责「没指定时用哪家」。
         */
        String defaultStore = storeMapper.selectList(Wrappers.<MchStore>lambdaQuery()
                        .eq(MchStore::getEntityNo, merchant.getEntityNo())
                        .eq(MchStore::getIsDefault, true)).stream()
                .map(MchStore::getStoreNo)
                .filter(storeNos::contains)
                .findFirst()
                .orElse(storeNos.stream().sorted().findFirst().orElse(null));

        return new BizContext(merchant.getEntityNo(), pickupNos, Set.of(), storeNos, defaultStore,
                Boolean.TRUE.equals(membership.getIsOwner()));
    }
}

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
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.MchRoleMapper roleDefMapper;

    public BizIdentityResolverImpl(MchEntityMapper merchantMapper, PickupQueryPort pickupQueryPort,
                                   ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper staffMapper,
                                   MchStoreMapper storeMapper, MchStoreRoleMapper roleMapper,
                                   ai.neargo.shop.merchant.mapper.MerchantMappers.MchRoleMapper roleDefMapper) {
        this.storeMapper = storeMapper;
        this.roleMapper = roleMapper;
        this.roleDefMapper = roleDefMapper;
        this.merchantMapper = merchantMapper;
        this.pickupQueryPort = pickupQueryPort;
        this.staffMapper = staffMapper;
    }

    /**
     * 角色 → 权限码（V71）：把「他在每家店是什么角色」翻译成「他在每家店能做什么」。
     *
     * <p><b>在这里翻译，判权那一刻就不用再查库</b>（见 {@link BizContext#can}）。
     * 每请求一次、随请求新鲜 —— 老板改了角色的权限，员工的下一个请求就生效，
     * 不用等他重新登录。收回权限必须立刻生效，这是把它放在这一步的主要理由。
     *
     * <p>查询条件是 {@code entity_no IN (本商家, '*')}：预置角色是全局共享的那一份，
     * 自定义角色属于这家商家。<b>少了任何一半都表现为「权限突然变少」</b>。
     *
     * <p>库里查不到的角色码（比如角色被删了而授权还在）**按零权限处理**，不抛错 ——
     * 认不出角色时给权限是这类判定最坏的失败方式。
     */
    private java.util.Map<String, Set<String>> permsByStore(
            String entityNo, java.util.Map<String, Set<String>> rolesByStore) {
        if (rolesByStore.isEmpty()) {
            return java.util.Map.of();
        }
        Set<String> used = rolesByStore.values().stream()
                .flatMap(Set::stream).collect(Collectors.toSet());
        java.util.Map<String, Set<String>> permsOfRole = roleDefMapper.selectList(
                        Wrappers.<ai.neargo.shop.merchant.entity.MchRole>lambdaQuery()
                                .in(ai.neargo.shop.merchant.entity.MchRole::getEntityNo,
                                        java.util.List.of(entityNo,
                                                ai.neargo.shop.merchant.entity.MchRole.BUILTIN_ENTITY))
                                .in(ai.neargo.shop.merchant.entity.MchRole::getRoleCode, used))
                .stream()
                .collect(Collectors.toMap(
                        ai.neargo.shop.merchant.entity.MchRole::getRoleCode,
                        r -> parsePerms(r.getPerms()),
                        // 同一个码既有预置又有自定义时以**自定义**为准：
                        // 唯一键拦住了同名，这里只是兜底，不该静默丢一份
                        (builtin, custom) -> custom));

        java.util.Map<String, Set<String>> out = new java.util.HashMap<>();
        rolesByStore.forEach((storeNo, roles) -> out.put(storeNo, roles.stream()
                .flatMap(r -> permsOfRole.getOrDefault(r, Set.of()).stream())
                .collect(Collectors.toUnmodifiableSet())));
        return out;
    }

    /** `["biz:a","biz:b"]` → Set。手写解析：只有这一处用，引一个 JSON 库不划算 */
    private static Set<String> parsePerms(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        return java.util.Arrays.stream(json.replaceAll("[\\[\\]\"]", "").split(","))
                .map(String::trim).filter(x -> !x.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
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

        /*
         * 我有权限的门店。**老板与店员不是同一套口径**：
         *   老板（is_owner）→ 主体下**全部**门店，包括他明天新建的那家；
         *   店员          → 只有 mch_store_role 里被授权到的那几家。
         *
         * 给店员按主体展开的话，加一个店员等于把所有门店都交出去 ——
         * 而「A 店店员能看到 B 店订单」这种越权不会报错，只会安静地多看到一些东西。
         */
        var membership = memberships.get(0);
        /*
         * 每家店上我持有的**全部**角色（V18 起一人一店可多角色）。
         *
         * 一次解析好放进 BizContext，切门店（X-Store-No）时不用重查库 ——
         * 而角色必须跟着门店走：同一个人可能在文三路店是店长、古墩路店是店员。
         */
        java.util.Map<String, Set<String>> rolesByStore = roleMapper.selectList(
                        Wrappers.<MchStoreRole>lambdaQuery()
                                .eq(MchStoreRole::getMchAccountNo, membership.getMchAccountNo()))
                .stream()
                .collect(Collectors.groupingBy(MchStoreRole::getStoreNo,
                        Collectors.mapping(MchStoreRole::getRole, Collectors.toUnmodifiableSet())));

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

        /*
         * 能核销哪些自提点：**按我能管的门店算**，不是按主体（V16 起自提点归属到门店）。
         *
         * 按主体算的话，A 店店员能核销 B 店门口那个自提点的货 —— 而门店授权
         * (mch_store_role) 明明已经把范围划出来了。这与订单作用域是同一条原则：
         * 越权不会报错，只会安静地多做一些事。
         */
        /*
         * 用 LinkedHashSet 保住顺序。
         *
         * 不是洁癖：PickupServiceImpl 拿 `pickupNos().iterator().next()` 当
         * 「默认自提点」。而 Set.copyOf 的迭代顺序**每次 JVM 启动都不一样**
         * （JDK 的不可变集合按启动时的随机盐排布），于是「默认点」会在
         * PP0001 和 PP0002 之间随机漂移 —— 测试里表现为偶发失败，
         * 线上表现为「今天进来看到的是另一家点的单」。
         *
         * 真正的修法是让那处显式挑一个（比如默认门店的点），但那是履约域的决定；
         * 在此之前，至少不要让顺序本身变成随机数。
         */
        Set<String> pickupNos =
                new java.util.LinkedHashSet<>(pickupQueryPort.activeStorePickupNos(storeNos));

        return new BizContext(merchant.getEntityNo(), pickupNos, Set.of(), storeNos, defaultStore,
                Boolean.TRUE.equals(membership.getIsOwner()), rolesByStore,
                permsByStore(merchant.getEntityNo(), rolesByStore));
    }
}

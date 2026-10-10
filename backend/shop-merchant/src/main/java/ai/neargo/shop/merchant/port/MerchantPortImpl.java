package ai.neargo.shop.merchant.port;

import ai.neargo.shop.common.Fulfillments;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.spi.user.MerchantAdminPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchQualification;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.entity.MchEntityCommunity;
import ai.neargo.shop.merchant.entity.MchServiceArea;
import ai.neargo.shop.merchant.entity.MchPaymentMerchant;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityCommunityMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchPaymentMapper;
import java.util.List;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.spi.reach.ConsumerProfile;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 商家域对外的两个 Port：查询（{@link MerchantQueryPort}）与开通（{@link MerchantAdminPort}）。
 *
 * <p>合成一个实现类而不是两个，因为它们共用同一张表与同一套字段口径；
 * 分开写会出现两处各自维护「什么算 ACTIVE」的判断。接口仍是两个 ——
 * 查询是所有域都用的，开通只有 platform（运营审核通过）能调，权限边界不同。
 *
 * <p>从 {@code MerchantServiceImpl} 抽出：Service 兼任 Port 时，
 * 改本域的商家详情逻辑会不知不觉改掉 trade / settle 依赖的跨域契约。
 */
@Component
public class MerchantPortImpl implements MerchantQueryPort, MerchantAdminPort,
        ai.neargo.shop.spi.user.MerchantRatingPort {

    private static final String ACTIVE = "ACTIVE";
    /** 履约能力（ADR-013）。值域与 mch_entity.fulfillment_reach 一致 */
    private static final String AREA_ACTIVE = "ACTIVE";
    private static final String AREA_COMMUNITY = "COMMUNITY";
    /** 评分存整数（50 = 5.0 分），避免浮点入库 */
    private static final int RATING_SCALE = 10;
    private static final int RATING_INIT = 50;

    /**
     * 一个账号最多持有几张证照（经营主体）。
     *
     * <p><b>这是防滥用的硬闸，不是可售的额度</b>：门店数量按套餐卖（{@code mch_entity_plan}），
     * 证照数量不卖 —— 它挡的是「把平台当批量注册工具」。所以写成常量而不是配置项：
     * 配置项会被当成一个可以调的旋钮，而这个数只该由平台按个案单独放开。
     */
    private static final int MAX_ENTITIES_PER_ACCOUNT = 5;

    private final MchEntityMapper merchantMapper;
    private final ai.neargo.shop.merchant.service.MerchantGovernService governService;
    /** 转存入驻资质时用来查重 —— 写侧仍走 governService，这里只读 */
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.QualificationMapper qualificationMapper;
    private final MchEntityCommunityMapper merchantCommunityMapper;
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.ServiceAreaMapper serviceAreaMapper;
    private final MchPaymentMapper merchantPaymentMapper;
    private final ai.neargo.shop.merchant.service.MerchantStoreService merchantStoreService;
    private final ai.neargo.shop.spi.user.CommunityQueryPort communityQueryPort;
    private final ai.neargo.shop.spi.platform.MasterDataPort masterDataPort;
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper staffMapper;
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper storeMapper;
    private final ai.neargo.shop.merchant.service.impl.StoreSenderResolver senderResolver;
    private final ai.neargo.shop.merchant.service.MerchantAuthCodeService authCodeService;
    /*
     * 保证金与欠款用 ObjectProvider 懒取：
     * DebtServiceImpl 依赖 AdmissionService，而准入那一侧又要问商家主档 ——
     * 构造期直接注入会绕成环，而环的报错信息（BeanCurrentlyInCreation）
     * 完全看不出是这两个类。
     */
    private final org.springframework.beans.factory.ObjectProvider<
            ai.neargo.shop.merchant.service.AdmissionService> admissionServiceProvider;
    private final org.springframework.beans.factory.ObjectProvider<
            ai.neargo.shop.merchant.service.DebtService> debtServiceProvider;
    private final tools.jackson.databind.ObjectMapper json;
    /** 主体激活时建 FREE 订阅行（V150）—— 与 ensureDefaultStore 同一类动作 */
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.EntityPlanMapper entityPlanMapper;
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.PlanDefMapper planDefMapper;
    /** 门店送货方式（方案 v4）—— 可见性与下单闸的取数口 */
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.FulfillmentChannelMapper fulfillmentChannelMapper;
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.ChannelPickupMapper channelPickupMapper;
    private final ai.neargo.shop.spi.user.PickupQueryPort pickupQueryPort;
    /** 「送不送得到」的配置读取；判定本身在 {@link ai.neargo.shop.merchant.reach.ReachRule} */
    private final ai.neargo.shop.merchant.reach.StoreReachLoader reachLoader;
    /** 可见范围判定的唯一入口（ADR-034）。正向命中走索引点查、反向展开走内存，同一条 ReachRule.decide */
    private final ai.neargo.shop.merchant.reach.ReachMatcher reachMatcher;
    private final ai.neargo.shop.geo.ReachGeoProps geoProps;


    public MerchantPortImpl(MchEntityMapper merchantMapper, MchEntityCommunityMapper merchantCommunityMapper,
                            MchPaymentMapper merchantPaymentMapper,
                            ai.neargo.shop.merchant.service.MerchantStoreService merchantStoreService,
                            ai.neargo.shop.spi.user.CommunityQueryPort communityQueryPort,
                            ai.neargo.shop.spi.platform.MasterDataPort masterDataPort,
                            ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper staffMapper,
                            ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper storeMapper,
                            ai.neargo.shop.merchant.service.impl.StoreSenderResolver senderResolver,
                            tools.jackson.databind.ObjectMapper json,
                            ai.neargo.shop.merchant.service.MerchantGovernService governService,
                            ai.neargo.shop.merchant.mapper.MerchantMappers.ServiceAreaMapper serviceAreaMapper,
                            ai.neargo.shop.merchant.mapper.MerchantMappers.QualificationMapper qualificationMapper,
                            ai.neargo.shop.merchant.mapper.MerchantMappers.EntityPlanMapper entityPlanMapper,
                            ai.neargo.shop.merchant.mapper.MerchantMappers.PlanDefMapper planDefMapper,
                            ai.neargo.shop.merchant.service.MerchantAuthCodeService authCodeService,
                            ai.neargo.shop.merchant.mapper.MerchantMappers.FulfillmentChannelMapper fulfillmentChannelMapper,
                            ai.neargo.shop.merchant.mapper.MerchantMappers.ChannelPickupMapper channelPickupMapper,
                            ai.neargo.shop.spi.user.PickupQueryPort pickupQueryPort,
                            ai.neargo.shop.merchant.reach.StoreReachLoader reachLoader,
                            ai.neargo.shop.merchant.reach.ReachMatcher reachMatcher,
                            ai.neargo.shop.geo.ReachGeoProps geoProps,
                            org.springframework.beans.factory.ObjectProvider<
                                    ai.neargo.shop.merchant.service.AdmissionService> admissionServiceProvider,
                            org.springframework.beans.factory.ObjectProvider<
                                    ai.neargo.shop.merchant.service.DebtService> debtServiceProvider) {
        this.admissionServiceProvider = admissionServiceProvider;
        this.debtServiceProvider = debtServiceProvider;
        this.fulfillmentChannelMapper = fulfillmentChannelMapper;
        this.channelPickupMapper = channelPickupMapper;
        this.pickupQueryPort = pickupQueryPort;
        this.reachLoader = reachLoader;
        this.reachMatcher = reachMatcher;
        this.geoProps = geoProps;
        this.authCodeService = authCodeService;
        this.entityPlanMapper = entityPlanMapper;
        this.planDefMapper = planDefMapper;
        this.qualificationMapper = qualificationMapper;
        this.governService = governService;
        this.json = json;
        this.staffMapper = staffMapper;
        this.storeMapper = storeMapper;
        this.senderResolver = senderResolver;
        this.masterDataPort = masterDataPort;
        this.communityQueryPort = communityQueryPort;
        this.merchantCommunityMapper = merchantCommunityMapper;
        this.serviceAreaMapper = serviceAreaMapper;
        this.merchantPaymentMapper = merchantPaymentMapper;
        this.merchantMapper = merchantMapper;
        this.merchantStoreService = merchantStoreService;
    }

    @Override
    public void grantCategoryCodes(String entityNo, List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            // 只卖无门槛类目的商家不需要任何码 —— 空不是「忘了填」
            return;
        }
        /*
         * 走 setCodes 而不是直接写字段：那里有三道校验（主体在营、码存在、写没写进去），
         * 绕过去的话「审核时授的码」与「事后调整的码」会有两套规则，
         * 而两套规则里总有一套是错的。
         */
        authCodeService.setCodes(entityNo, codes, "入驻审核通过时授予");
    }

    @Override
    public List<String> reachableCommunities(String merchantNo) {
        // 主体口径 = 不指定门店。**行为与加这个重载之前逐字相同**
        return reachableCommunities(merchantNo, null);
    }

    /**
     * 按<b>门店</b>算可达：开放小区里，这家店任一开着的送货方式送得到的那些。
     *
     * <p>「送不送得到」只有一个判定 {@link ai.neargo.shop.merchant.reach.ReachRule}，
     * 这里只负责给候选（方案-商品可见性改查询时关联 §2.3）。此前这里自己展开范围、
     * 把一家店所有 SUBSET 路取并集整店一起裁，与结算时逐路判的那一份在四种情形下答案不同。
     *
     * <p>{@code storeNo} 为空 = 主体口径（各店开着的路取并集、一律按「全部」）——
     * 商家详情页那类「这家商家覆盖哪儿」是主体级的问题。
     */
    @Override
    public List<String> reachableCommunities(String merchantNo, String storeNo) {
        MchEntity m = activeEntity(merchantNo);
        if (m == null) {
            return List.of();
        }
        return reachMatcher.reachableCommunities(m, storeNo);
    }

    @Override
    public boolean serves(String merchantNo, String storeNo, String communityNo) {
        if (communityNo == null || communityNo.isBlank()) {
            return false;
        }
        return serves(merchantNo, storeNo, reachMatcher.profileOf(communityRef(communityNo)));
    }

    @Override
    public boolean serves(String merchantNo, String storeNo, ConsumerProfile profile) {
        if (profile == null) {
            return false;
        }
        MchEntity m = activeEntity(merchantNo);
        if (m == null) {
            return false;
        }
        if (storeNo == null || storeNo.isBlank()) {
            // 主体口径：任一 ACTIVE 门店送得到即算
            return !servingStores(profile).getOrDefault(merchantNo, java.util.Set.of()).isEmpty();
        }
        return reachMatcher.covers(storeNo, profile);
    }

    @Override
    public java.util.Map<String, java.util.Set<String>> servingStores(String communityNo) {
        if (communityNo == null || communityNo.isBlank()) {
            return java.util.Map.of();
        }
        return servingStores(reachMatcher.profileOf(communityRef(communityNo)));
    }

    @Override
    public java.util.Map<String, java.util.Set<String>> servingStores(ConsumerProfile profile) {
        return reachMatcher.servingStores(profile);
    }

    /**
     * 按区划反查。<b>区划码本身就是画像的一部分</b>（ADR-034）—— 不再「先找区里的开放小区、再逐个判」：
     * 那条路让「所在区没有运营开过小区」的消费者一片空白，而商家明明框了整个市。
     */
    @Override
    public java.util.Map<String, java.util.Set<String>> servingStoresInRegion(String regionCode) {
        if (regionCode == null || regionCode.isBlank()) {
            return java.util.Map.of();
        }
        return servingStores(ConsumerProfile.of(regionCode, null, null, null, null,
                geoProps.getS2MinLevel(), geoProps.getS2MaxLevel()));
    }

    @Override
    public java.util.List<StoreCoverage> storeCoverage() {
        return reachMatcher.storeCoverage().stream()
                .map(r -> new StoreCoverage(r.entityNo(), r.storeNo(), r.communityNos()))
                .toList();
    }

    /**
     * <b>没激活的主体对谁都不可见</b>（含无证照先开店的 {@code PENDING_LICENSE}）。
     * 进件没走完的商家，货不该被买家搜到、更不该走到下单 —— 闸门只在这一处，调用方一行不动。
     */
    private MchEntity activeEntity(String merchantNo) {
        if (merchantNo == null || merchantNo.isBlank()) {
            return null;
        }
        MchEntity m = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getEntityNo, merchantNo).last("limit 1")));
        return m != null && ACTIVE.equals(m.getStatus()) ? m : null;
    }

    /** 查不到的小区按「未开放、无区划、无上级」判 —— 只剩「范围里直接点名它」这一条能命中，与展开口径一致 */
    private ai.neargo.shop.spi.user.CommunityQueryPort.CommunityRef communityRef(String communityNo) {
        return communityQueryPort.communityRefs(List.of(communityNo)).getOrDefault(communityNo,
                new ai.neargo.shop.spi.user.CommunityQueryPort.CommunityRef(communityNo, null, null, false));
    }

    @Override
    public List<String> previewReachable(String merchantNo, List<String[]> areas) {
        return previewReachable(merchantNo, null, areas);
    }

    /** 预览<b>这家店</b>改成这份范围后覆盖哪儿（V381 范围门店级）。门店为空 = 默认店 */
    @Override
    public List<String> previewReachable(String merchantNo, String storeNo, List<String[]> areas) {
        // 与可见性同一条闸：没激活的主体对谁都不可见，预览也不该给他一个好看的数
        MchEntity m = activeEntity(merchantNo);
        if (m == null) {
            return List.of();
        }
        /*
         * 端上传来的那一份**当场变成范围行**（不落库）。status 一律按 ACTIVE：
         * 所有粒度都自选即生效（2026-08-24 起），预览没有「待审」这一档。
         */
        List<MchServiceArea> rows = areas == null ? List.<MchServiceArea>of() : areas.stream()
                .filter(a -> a != null && a.length >= 2 && a[0] != null && a[1] != null && !a[1].isBlank())
                .map(a -> {
                    var r = new MchServiceArea();
                    r.setEntityNo(merchantNo);
                    r.setLevel(a[0]);
                    r.setRefCode(a[1]);
                    r.setStatus(AREA_ACTIVE);
                    r.setMode(a.length > 2 && MchServiceArea.MODE_EXCLUDE.equals(a[2])
                            ? MchServiceArea.MODE_EXCLUDE : MchServiceArea.MODE_INCLUDE);
                    return r;
                })
                .toList();
        /*
         * 与保存后的可达同一个判定（ReachRule）。此前这里开着快递就直接「全部开放 − 排除」、
         * 不看框选 —— 而保存后的可达早已改成「快递也尊重框选」（#4②），
         * 于是商家在预览里看到「全国」，保存之后实际只覆盖框的那几块。
         */
        return reachMatcher.previewReachable(m, storeNo, rows);
    }

    @Override
    public java.util.Map<String, int[]> coordsOfStores(java.util.Collection<String> storeNos) {
        if (storeNos == null || storeNos.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<String, int[]> out = new java.util.HashMap<>();
        DataScopeContext.executeWithoutScope(() -> storeMapper.selectList(
                        Wrappers.<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                                .in(ai.neargo.shop.merchant.entity.MchStore::getStoreNo, storeNos)))
                .forEach(st -> {
                    // 同 coordsOfCommunities：没标点的不放进来，别拿 (0,0) 算出一个看着正常的数
                    if (st.getLatE6() != null && st.getLngE6() != null) {
                        out.put(st.getStoreNo(), new int[]{st.getLatE6(), st.getLngE6()});
                    }
                });
        return out;
    }

    @Override
    public java.util.Set<String> allowedPickupNos(String merchantNo) {
        if (merchantNo == null || merchantNo.isBlank()) {
            return java.util.Set.of();
        }
        java.util.List<String> stores = storeNos(merchantNo);
        if (stores.isEmpty()) {
            return java.util.Set.of();
        }
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        // 门店引用的社区自提点（方案 v4 mch_channel_pickup）——绕域理由同 enabledFulfillments
        DataScopeContext.executeWithoutScope(() -> channelPickupMapper.selectList(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers
                                .<ai.neargo.shop.merchant.entity.MchChannelPickup>lambdaQuery()
                                .in(ai.neargo.shop.merchant.entity.MchChannelPickup::getStoreNo, stores)))
                .forEach(r -> out.add(r.getPickupNo()));
        // 门店自己的 STORE 点：门店自取的落点
        out.addAll(pickupQueryPort.activeStorePickupNos(stores));
        return out;
    }

    @Override
    public java.util.Set<String> enabledFulfillmentsFor(String merchantNo, String storeNo, String communityNo) {
        java.util.Set<String> enabled = enabledFulfillments(merchantNo, storeNo);
        if (enabled.isEmpty() || communityNo == null || communityNo.isBlank()) {
            return enabled;
        }
        MchEntity m = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getEntityNo, merchantNo).last("limit 1")));
        if (m == null) {
            return java.util.Set.of();
        }
        /*
         * 逐路判「这一路送不送得到买家的小区」，与可见性同一个判定（ReachRule）。
         *
         * 此前这里另写了一份：只对 SUBSET 那几路判、且小区不含楼栋、不减 EXCLUDE；
         * 「全部」那几路一律放行、不看主体框选 —— 于是「看得见、结算说不送」或者反过来。
         * 主体状态不在这里判：那是另一道闸，这里只回答「送不送得到」。
         */
        ConsumerProfile profile = reachMatcher.profileOf(communityRef(communityNo));
        // 门店为空 = 主体口径：各 ACTIVE 门店逐个判，送得到的路取并集（V381 范围门店级）
        List<String> stores = storeNo == null || storeNo.isBlank()
                ? activeStoreNos(merchantNo) : List.of(storeNo);
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String st : stores) {
            for (String channel : enabled) {
                if (reachMatcher.selectable(st, channel, profile)) {
                    out.add(channel);
                }
            }
        }
        return out;
    }

    @Override
    public java.util.Set<String> enabledFulfillments(String merchantNo, String storeNo) {
        if (merchantNo == null || merchantNo.isBlank()) {
            return java.util.Set.of();
        }
        /*
         * 绕开数据域：与本类其余读一致 —— 可见性与下单闸是全局判断，
         * 不该因为调用方带着某个数据域就看不见 channel 行。
         */
        List<ai.neargo.shop.merchant.entity.MchFulfillmentChannel> rows =
                DataScopeContext.executeWithoutScope(() -> fulfillmentChannelMapper.selectList(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers
                                .<ai.neargo.shop.merchant.entity.MchFulfillmentChannel>lambdaQuery()
                                .eq(ai.neargo.shop.merchant.entity.MchFulfillmentChannel::getEntityNo, merchantNo)
                                .eq(storeNo != null && !storeNo.isBlank(),
                                        ai.neargo.shop.merchant.entity.MchFulfillmentChannel::getStoreNo, storeNo)));
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (var row : rows) {
            // 运营锁路（P2）：锁着的路买家侧不可选
            if (Boolean.TRUE.equals(row.getEnabled()) && !Boolean.TRUE.equals(row.getOpsLocked())) {
                out.add(row.getChannel());
            }
        }
        // 「有行但全关」与「无行」都返回空集：前者写入口本就拦着（READONLY 门店除外，
        // 而它不接新单），调用方把空集一律当「未迁移，按旧口径放行」不会放出真单
        return out;
    }

    @Override
    public java.util.Map<String, String> storeNames(java.util.Collection<String> storeNos) {
        if (storeNos == null || storeNos.isEmpty()) {
            return java.util.Map.of();
        }
        /*
         * **不解数据域。** 看板传进来的门店号来自已接域的埋点/归因查询，本来就在权限内；
         * 再解一次域没有任何用处，只会让越权的行有机会漏进来。
         * （第一版这里解了域，被 ops-data-scope 守卫抓了出来。）
         */
        var rows = storeMapper.selectList(
                Wrappers.<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                        .in(ai.neargo.shop.merchant.entity.MchStore::getStoreNo, storeNos));
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        for (var r : rows) {
            if (r.getName() != null && !r.getName().isBlank()) {
                out.put(r.getStoreNo(), r.getName());
            }
        }
        return out;
    }

    @Override
    public java.util.Map<String, String> defaultStoreNos(java.util.Collection<String> merchantNos) {
        if (merchantNos == null || merchantNos.isEmpty()) {
            return java.util.Map.of();
        }
        // 一次查完（逐个是 N+1），且**不解域** —— 见接口上那段与 defaultStoreNo 的区别
        var rows = storeMapper.selectList(
                Wrappers.<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                        .in(ai.neargo.shop.merchant.entity.MchStore::getEntityNo, merchantNos)
                        .eq(ai.neargo.shop.merchant.entity.MchStore::getIsDefault, true));
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        for (var r : rows) {
            out.putIfAbsent(r.getEntityNo(), r.getStoreNo());
        }
        return out;
    }

    @Override
    public Optional<String> defaultStoreNo(String merchantNo) {
        if (merchantNo == null || merchantNo.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(DataScopeContext.executeWithoutScope(() ->
                        storeMapper.selectOne(Wrappers.<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                                .eq(ai.neargo.shop.merchant.entity.MchStore::getEntityNo, merchantNo)
                                .eq(ai.neargo.shop.merchant.entity.MchStore::getIsDefault, true)
                                .last("limit 1"))))
                .map(ai.neargo.shop.merchant.entity.MchStore::getStoreNo);
    }

    /**
     * 门面文案取<b>默认门店</b>那一条：C 端的门店主页是按主体进的，
     * 多门店时展示主店的公告与地址（要看分店得从自提点那条路进）。
     */
    @Override
    public Optional<StoreFront> storeFront(String merchantNo) {
        if (merchantNo == null || merchantNo.isBlank()) {
            return Optional.empty();
        }
        var store = DataScopeContext.executeWithoutScope(() ->
                storeMapper.selectOne(Wrappers.<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchStore::getEntityNo, merchantNo)
                        .orderByDesc(ai.neargo.shop.merchant.entity.MchStore::getIsDefault)
                        .last("limit 1")));
        return store == null ? Optional.empty()
                // 过期即空：与 B 端 profile() 同一个判断，只写在实体上那一处
                : Optional.of(new StoreFront(store.effectiveAnnouncement(),
                        // 过期的公告连时间也不给：那一行整个不该出现，给了时间反而像它还在
                        store.effectiveAnnouncement().isEmpty() ? null : store.getAnnouncementAt(),
                        nvl(store.getOpenHours()), nvl(store.getAddress()),
                        nvl(store.getStatus()), store.getLatE6(), store.getLngE6(),
                        nvl(store.getBannerUrl())));
    }

    @Override
    public StoreCoordHealth storeCoordHealth() {
        /*
         * **走数据域，不绕。**
         *
         * 一开始写的是 `executeWithoutScope`，理由是「平台级分母，配了域的运营
         * 也该看到全量」。那个理由站不住：这一页是给不受限的管理员看的，
         * 而他本来就没有域限制、照样看到全部。绕域换来的只是**让配了域的运营
         * 看到他无权处置、也点不进去的别家门店** —— 既是泄漏又没用。
         */
        List<ai.neargo.shop.merchant.entity.MchStore> stores = storeMapper.selectList(
                Wrappers.<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery());
        List<StoreCoordHealth.MissingStore> missing = new java.util.ArrayList<>();
        int withCoords = 0;
        for (var st : stores) {
            if (st.getLatE6() != null && st.getLngE6() != null) {
                withCoords++;
            } else {
                /*
                 * **不在这里取商家名。** 取名字要调 findAll，而它为 C 端刻意绕开了
                 * 数据域（见那个方法的注释）—— 从 ops 读路径调它，配了域的运营
                 * 就会看到别家的商家名，而且不报错。名字交给前端按 merchantNo 自取。
                 */
                missing.add(new StoreCoordHealth.MissingStore(
                        st.getStoreNo(), st.getName(), st.getEntityNo(), st.getDeliveryRadiusM()));
            }
        }
        return new StoreCoordHealth(stores.size(), withCoords, List.copyOf(missing));
    }

    @Override
    public Optional<DeliveryOrigin> deliveryOrigin(String merchantNo, String storeNo) {
        if (merchantNo != null && !merchantNo.isBlank() && storeNo != null && !storeNo.isBlank()) {
            var store = DataScopeContext.executeWithoutScope(() ->
                    storeMapper.selectOne(Wrappers.<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                            .eq(ai.neargo.shop.merchant.entity.MchStore::getEntityNo, merchantNo)
                            .eq(ai.neargo.shop.merchant.entity.MchStore::getStoreNo, storeNo)
                            .last("limit 1")));
            if (store != null && store.getLatE6() != null && store.getLngE6() != null) {
                return Optional.of(new DeliveryOrigin(store.getLatE6(), store.getLngE6(),
                        store.getDeliveryRadiusM() == null ? 0 : store.getDeliveryRadiusM()));
            }
        }
        // 没指定门店 / 那家店没标点：回落主体默认店（与改造前逐字相同）
        return deliveryOrigin(merchantNo);
    }

    @Override
    public Optional<StoreSender> storeSender(String merchantNo, String storeNo) {
        if (merchantNo == null || merchantNo.isBlank() || storeNo == null || storeNo.isBlank()) {
            return Optional.empty();
        }
        var store = DataScopeContext.executeWithoutScope(() ->
                storeMapper.selectOne(Wrappers.<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchStore::getEntityNo, merchantNo)
                        .eq(ai.neargo.shop.merchant.entity.MchStore::getStoreNo, storeNo)
                        .last("limit 1")));
        // 口径只在 StoreSenderResolver 一处：发货设置优先，没填的回落门店名 / 店主手机 / 门店地址
        return store == null ? Optional.empty() : Optional.of(senderResolver.effective(store));
    }

    @Override
    public Optional<String> expressTemplateNo(String merchantNo, String storeNo) {
        if (merchantNo == null || merchantNo.isBlank() || storeNo == null || storeNo.isBlank()) {
            return Optional.empty();
        }
        var row = DataScopeContext.executeWithoutScope(() -> fulfillmentChannelMapper.selectOne(
                Wrappers.<ai.neargo.shop.merchant.entity.MchFulfillmentChannel>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchFulfillmentChannel::getEntityNo, merchantNo)
                        .eq(ai.neargo.shop.merchant.entity.MchFulfillmentChannel::getStoreNo, storeNo)
                        .eq(ai.neargo.shop.merchant.entity.MchFulfillmentChannel::getChannel, Fulfillments.EXPRESS)
                        .last("limit 1")));
        return Optional.ofNullable(row == null ? null
                : ai.neargo.shop.merchant.service.impl.StoreFulfillmentServiceImpl.templateNoOf(row.getConfig()));
    }

    @Override
    public Optional<DeliveryOrigin> deliveryOrigin(String merchantNo) {
        if (merchantNo == null || merchantNo.isBlank()) {
            return Optional.empty();
        }
        var store = DataScopeContext.executeWithoutScope(() ->
                storeMapper.selectOne(Wrappers.<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchStore::getEntityNo, merchantNo)
                        .orderByDesc(ai.neargo.shop.merchant.entity.MchStore::getIsDefault)
                        .last("limit 1")));
        // 没标过点 = 这条规则不成立，返回空让调用方放行
        if (store == null || store.getLatE6() == null || store.getLngE6() == null) {
            return Optional.empty();
        }
        return Optional.of(new DeliveryOrigin(store.getLatE6(), store.getLngE6(),
                store.getDeliveryRadiusM() == null ? 0 : store.getDeliveryRadiusM()));
    }

    /** 空字符串而不是 null：端上直接渲染，null 会变成屏幕上的「null」 */
    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    /** 买家页那一行最多列几个地名。再多就该说「等 N 个地区」了 */
    private static final int SALE_SCOPE_SAMPLE = 6;

    @Override
    public SaleScope saleScope(String merchantNo) {
        MchEntity m = merchantNo == null || merchantNo.isBlank() ? null
                : DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getEntityNo, merchantNo).last("limit 1")));
        if (m == null) {
            return new SaleScope(false, java.util.List.of(), 0);
        }
        /*
         * 判据与可见性同一个（ReachRule）：**框了 INCLUDE 就按框选列地名，快递也一样**（#4②）。
         *
         * 2026-09-29 那一版写的是「开了快递就不限、根本不看框选」—— 当时可见性确实如此；
         * 后来可见性改成「快递也尊重框选」，这里没跟上：框了「深圳市 + 盐湖区」又开快递的商家，
         * 买家详情页写着「不限地区」，而他在第三个区根本搜不到这件货。
         * 现在两处调同一个判定，不会再一处改了另一处没改。
         */
        /*
         * 经营范围门店级之后（V381），同一条范围在几家店各一行 —— 列地名要按 (层级, 编码) 去重，
         * 否则「深圳市」会因为四家店都框了它而在买家页上出现四次。
         * 只算 ACTIVE 门店的：停用的店不卖，它框过的地方不该写进「可售地区」。
         */
        /*
         * **先判「有没有一家店不限」**，再列框选。范围门店级之后（V381）两者能同时成立：
         * 默认店全国发快递、分店只框了乌鲁木齐 —— 先列框选的话买家页只写「乌鲁木齐」，
         * 而可见性（各店并集）是全国。主体口径与可见性同一个：任一 ACTIVE 门店不限，这件货就不限。
         */
        var reaches = reachLoader.loadEach(m);
        var unlimitedStores = reaches.stream().filter(ai.neargo.shop.merchant.reach.ReachRule::unlimited).toList();
        if (!unlimitedStores.isEmpty()) {
            return new SaleScope(true, java.util.List.of(), 0,
                    excludedRegionNames(unlimitedStores, reaches.stream()
                            .filter(r -> !ai.neargo.shop.merchant.reach.ReachRule.unlimited(r)).toList()));
        }
        java.util.Set<String> activeStores = new java.util.HashSet<>(activeStoreNos(merchantNo));
        java.util.Map<String, MchServiceArea> uniq = new java.util.LinkedHashMap<>();
        DataScopeContext.executeWithoutScope(() ->
                        serviceAreaMapper.selectList(Wrappers.<MchServiceArea>lambdaQuery()
                                .eq(MchServiceArea::getEntityNo, merchantNo)
                                .eq(MchServiceArea::getStatus, AREA_ACTIVE)))
                .stream()
                .filter(a -> !MchServiceArea.MODE_EXCLUDE.equals(a.getMode()))
                .filter(a -> a.getStoreNo() != null && activeStores.contains(a.getStoreNo()))
                // 只列**有地名**的范围项：UNLIMITED 的 ref 是 `*`、POLYGON 的是几何指纹，
                // 列出来是一串无意义的字符。走到这里说明没有「不限」（上面已判），
                // 只开自提却留着 UNLIMITED 行就是这种情形
                .filter(a -> MchServiceArea.ADMIN_LEVELS.contains(a.getLevel())
                        || MchServiceArea.LEVEL_COMMUNITY.equals(a.getLevel()))
                .forEach(a -> uniq.putIfAbsent(a.getLevel() + "|" + a.getRefCode(), a));
        List<MchServiceArea> includes = List.copyOf(uniq.values());
        if (!includes.isEmpty()) {
            return new SaleScope(false,
                    includes.stream().limit(SALE_SCOPE_SAMPLE).map(this::buyerAreaName).toList(),
                    includes.size());
        }
        /*
         * 一条 INCLUDE 都没有时，空的含义**由履约路决定** —— 这是本方法唯一真正的判断，
         * 也是把它做在后端而不是留给端上的理由：同一个空数组，
         * 开了快递或自送是「不限」，只做自提是「谁也看不到」。
         * 端上拿到一个空列表判不出来，而判反的代价是给买家一句正好相反的承诺。
         *
         * 判据与 reachableCommunities 同一段（channel 集合为空则回落旧列），
         * 不另写一遍：另写的那份迟早与可见性分叉，届时页面上写着「不限地区」
         * 而这件商品在买家那儿根本搜不到。
         */
        // 没有门店不限（上面已判），也一条纳入都没有：只做自提、谁也看不到 —— 不说话
        return new SaleScope(false, java.util.List.of(), 0);
    }

    /**
     * 「不限地区（新疆、西藏除外）」括号里那几个。
     *
     * <p>主体口径是各店并集：只有<b>每一家</b>不限门店都排除了、且<b>没有</b>任何限定门店框进去的地方，
     * 买家在那儿才真的看不到 —— 少一个条件就是对买家说「新疆除外」而新疆其实有一家店在送。
     * 只收区划级：小区/楼栋级的「除 3 幢」对外地买家是噪音（TDD-经营范围排除地区 §3）。
     */
    private java.util.List<String> excludedRegionNames(
            java.util.List<ai.neargo.shop.merchant.reach.ReachRule.StoreReach> unlimitedStores,
            java.util.List<ai.neargo.shop.merchant.reach.ReachRule.StoreReach> limitedStores) {
        java.util.Set<String> common = null;
        for (var s : unlimitedStores) {
            java.util.Set<String> mine = new java.util.LinkedHashSet<>();
            for (var a : s.excludes()) {
                if (!"COMMUNITY".equals(a.level()) && a.refCode() != null && !a.refCode().isBlank()) {
                    mine.add(a.refCode());
                }
            }
            if (common == null) {
                common = mine;
            } else {
                common.retainAll(mine);
            }
        }
        if (common == null || common.isEmpty()) {
            return java.util.List.of();
        }
        // 限定门店框进去的（含上下级重叠）就不能说「除外」。小区级纳入挂不出区划码，宁可漏说不错说：
        // 漏说是买家页少一句话，错说是对一个确实送得到的地方说「不卖」。
        java.util.List<String> codes = common.stream()
                .filter(code -> limitedStores.stream().flatMap(r -> r.includes().stream())
                        .noneMatch(i -> "COMMUNITY".equals(i.level())
                                || i.refCode().startsWith(code) || code.startsWith(i.refCode())))
                .toList();
        if (codes.isEmpty()) {
            return java.util.List.of();
        }
        var names = masterDataPort.regionNames(codes);
        return codes.stream().map(c -> {
            String n = names.get(c);
            return n == null || n.isBlank() ? c : n;
        }).toList();
    }

    @Override
    public java.util.Set<String> excludedProvinces(String merchantNo, String storeNo) {
        if (merchantNo == null || merchantNo.isBlank()) {
            return java.util.Set.of();
        }
        String sNo = storeNo == null || storeNo.isBlank() ? defaultStoreNo(merchantNo).orElse(null) : storeNo;
        if (sNo == null) {
            return java.util.Set.of();
        }
        // 排除不看 status（与 ReachRule 同一口径：待审的排除也立即生效）
        return DataScopeContext.executeWithoutScope(() ->
                        serviceAreaMapper.selectList(Wrappers.<MchServiceArea>lambdaQuery()
                                .eq(MchServiceArea::getEntityNo, merchantNo)
                                .eq(MchServiceArea::getStoreNo, sNo)
                                .eq(MchServiceArea::getLevel, "PROVINCE")
                                .eq(MchServiceArea::getMode, MchServiceArea.MODE_EXCLUDE)))
                .stream().map(MchServiceArea::getRefCode)
                .filter(c -> c != null && !c.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * 覆盖项给买家看的名字。区划取<b>叶子名</b>：
     * 运营那份走 {@code regionPathName}（整条路径，为的是不看错），
     * 买家看到自己家那三个字就够，路径只会把详情页那一行挤成两行。
     * 取不到名就退回编码，<b>不返回空</b> —— 空会让整行少一个地方，看不出来。
     */
    private String buyerAreaName(MchServiceArea a) {
        String name = "COMMUNITY".equals(a.getLevel())
                ? communityQueryPort.communityName(a.getRefCode())
                : masterDataPort.regionNames(java.util.List.of(a.getRefCode())).get(a.getRefCode());
        return name == null || name.isBlank() ? a.getRefCode() : name;
    }

    @Override
    public java.util.List<String> storeNos(String merchantNo) {
        if (merchantNo == null || merchantNo.isBlank()) {
            return java.util.List.of();
        }
        return DataScopeContext.executeWithoutScope(() ->
                        storeMapper.selectList(Wrappers.<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                                .eq(ai.neargo.shop.merchant.entity.MchStore::getEntityNo, merchantNo)
                                .orderByAsc(ai.neargo.shop.merchant.entity.MchStore::getId)))
                .stream().map(ai.neargo.shop.merchant.entity.MchStore::getStoreNo).toList();
    }

    @Override
    public java.util.List<String> activeStoreNos(String merchantNo) {
        if (merchantNo == null || merchantNo.isBlank()) {
            return java.util.List.of();
        }
        return DataScopeContext.executeWithoutScope(() ->
                        storeMapper.selectList(Wrappers.<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                                .eq(ai.neargo.shop.merchant.entity.MchStore::getEntityNo, merchantNo)
                                .eq(ai.neargo.shop.merchant.entity.MchStore::getStatus,
                                        ai.neargo.shop.merchant.entity.MchStore.ACTIVE)
                                .orderByAsc(ai.neargo.shop.merchant.entity.MchStore::getId)))
                .stream().map(ai.neargo.shop.merchant.entity.MchStore::getStoreNo).toList();
    }

    @Override
    public java.util.Map<String, String> entityOfStores(java.util.Collection<String> storeNos) {
        if (storeNos == null || storeNos.isEmpty()) {
            return java.util.Map.of();
        }
        return DataScopeContext.executeWithoutScope(() ->
                        storeMapper.selectList(Wrappers.<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                                .in(ai.neargo.shop.merchant.entity.MchStore::getStoreNo, storeNos)))
                .stream()
                .filter(s -> s.getStoreNo() != null && s.getEntityNo() != null)
                .collect(java.util.stream.Collectors.toMap(
                        ai.neargo.shop.merchant.entity.MchStore::getStoreNo,
                        ai.neargo.shop.merchant.entity.MchStore::getEntityNo,
                        (a, b) -> a));
    }

    @Override
    public String businessModeOf(String merchantNo, String storeNo) {
        MchStore store = null;
        if (storeNo != null && !storeNo.isBlank()) {
            store = DataScopeContext.executeWithoutScope(() ->
                    storeMapper.selectOne(Wrappers.<MchStore>lambdaQuery()
                            .eq(MchStore::getStoreNo, storeNo).last("limit 1")));
        }
        if (store == null && merchantNo != null && !merchantNo.isBlank()) {
            // 没传门店（或门店查不到）时回落主体的默认门店 —— 与 defaultStoreNo 同一口径
            store = DataScopeContext.executeWithoutScope(() ->
                    storeMapper.selectOne(Wrappers.<MchStore>lambdaQuery()
                            .eq(MchStore::getEntityNo, merchantNo)
                            .eq(MchStore::getIsDefault, true).last("limit 1")));
        }
        /*
         * 解析不出一律回落自营。**保守方向是有讲究的**：
         * 误判为自营，后果是多要一张进项票（可补）；
         * 误判为第三方，后果是去下发分账而对方根本没有二级商户号 —— 那是脏数据。
         */
        if (store == null || store.getBusinessMode() == null || store.getBusinessMode().isBlank()) {
            return MchStore.SELF_OPERATED;
        }
        return store.getBusinessMode();
    }

    @Override
    public Optional<String> payMerchantNoOf(String merchantNo, String storeNo) {
        if (merchantNo == null || merchantNo.isBlank()) {
            return Optional.empty();
        }
        /*
         * 只认**本主体已 ACTIVE** 的收款号 —— 与 StoreAdminServiceImpl.setPayment 同一条门槛。
         * 门店上存着的号可能在配好之后被停用（进件被驳回、账户被冻结），
         * 那时不能继续往它打款：不校验的话，症状是打款接口报错而账面显示已打，
         * 比一开始就解析不出号难查得多。
         */
        java.util.Set<String> usable = DataScopeContext.executeWithoutScope(() ->
                        merchantPaymentMapper.selectList(
                                Wrappers.<ai.neargo.shop.merchant.entity.MchPaymentMerchant>lambdaQuery()
                                        .eq(ai.neargo.shop.merchant.entity.MchPaymentMerchant::getEntityNo, merchantNo)
                                        .eq(ai.neargo.shop.merchant.entity.MchPaymentMerchant::getApplyStatus,
                                                ai.neargo.shop.merchant.entity.MchPaymentMerchant.ACTIVE)))
                .stream()
                .map(ai.neargo.shop.merchant.entity.MchPaymentMerchant::getPayMerchantNo)
                .filter(x -> x != null && !x.isBlank())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (usable.isEmpty()) {
            return Optional.empty();
        }

        if (storeNo != null && !storeNo.isBlank()) {
            String configured = Optional.ofNullable(DataScopeContext.executeWithoutScope(() ->
                            storeMapper.selectOne(Wrappers.<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                                    .eq(ai.neargo.shop.merchant.entity.MchStore::getEntityNo, merchantNo)
                                    .eq(ai.neargo.shop.merchant.entity.MchStore::getStoreNo, storeNo)
                                    .last("limit 1"))))
                    .map(ai.neargo.shop.merchant.entity.MchStore::getPayMerchantNo)
                    .orElse(null);
            // 配了但已不可用 → 落回主体默认号，而不是解析失败：
            // 钱照收了，不能因为商家把号停了就把这笔结算卡死
            if (configured != null && !configured.isBlank() && usable.contains(configured)) {
                return Optional.of(configured);
            }
        }
        // 主体默认号 = 默认门店配的号；默认门店也没配就取第一个可用号。
        // 「第一个」是稳定的：查询按 id 顺序，进件先后不会因为查询而变
        String byDefaultStore = defaultStoreNo(merchantNo)
                .map(sn -> DataScopeContext.executeWithoutScope(() ->
                        storeMapper.selectOne(Wrappers.<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                                .eq(ai.neargo.shop.merchant.entity.MchStore::getStoreNo, sn)
                                .last("limit 1"))))
                .map(ai.neargo.shop.merchant.entity.MchStore::getPayMerchantNo)
                .filter(x -> x != null && !x.isBlank() && usable.contains(x))
                .orElse(null);
        return Optional.of(byDefaultStore != null ? byDefaultStore : usable.iterator().next());
    }

    @Override
    public Optional<MerchantBrief> find(String merchantNo) {
        MchEntity m = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                .eq(MchEntity::getEntityNo, merchantNo).last("limit 1")));
        if (m == null) {
            return Optional.empty();
        }
        /*
         * canReceive 目前等同于 ACTIVE；S6 接分账后改为「接收方已报备」（ADR-002）。
         * 调用方（trade / settle）届时一行不用改 —— 这正是 Port 的作用：
         * 「能不能收钱」这个判断的口径变了，问的人不必知道。
         */
        boolean active = ACTIVE.equals(m.getStatus());
        return Optional.of(new MerchantBrief(m.getEntityNo(), m.getName(), active, active,
                m.getLogo(), m.getRating() == null ? 0d : m.getRating() / (double) RATING_SCALE,
                m.getRatingCount() == null ? 0 : m.getRatingCount(),
                Boolean.TRUE.equals(m.getVerified()),
                m.getBreachCount() == null ? 0 : m.getBreachCount(),
                Integer.valueOf(1).equals(m.getSelfOperated())));
    }

    @Override
    public java.util.Map<String, MerchantBrief> findAll(java.util.Collection<String> merchantNos) {
        if (merchantNos == null || merchantNos.isEmpty()) {
            return java.util.Map.of();
        }
        /*
         * executeWithoutScope：调用方是 C 端（收藏列表、自提点归属），本来就该看到
         * 别家的商家名与 logo。不解除数据域的话，这里会按当前登录商家过滤，
         * C 端用户拿到的永远是空列表 —— 而且不报错。
         */
        java.util.List<MchEntity> rows = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectList(Wrappers.<MchEntity>lambdaQuery()
                        .in(MchEntity::getEntityNo, merchantNos)));
        java.util.Map<String, MerchantBrief> out = new java.util.LinkedHashMap<>();
        for (MchEntity m : rows) {
            boolean active = ACTIVE.equals(m.getStatus());
            out.put(m.getEntityNo(), new MerchantBrief(m.getEntityNo(), m.getName(), active, active,
                    m.getLogo(), m.getRating() == null ? 0d : m.getRating() / (double) RATING_SCALE,
                    m.getRatingCount() == null ? 0 : m.getRatingCount(),
                    Boolean.TRUE.equals(m.getVerified()),
                    m.getBreachCount() == null ? 0 : m.getBreachCount(),
                    Integer.valueOf(1).equals(m.getSelfOperated())));
        }
        return out;
    }

    @Override
    public String fundsModeOf(String merchantNo) {
        MchEntity m = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getEntityNo, merchantNo).last("LIMIT 1")));
        // 查不到按归集：那是今天唯一在跑的路径，而误判成直连会让系统去执行
        // 一次**本不存在的补差** —— 应付已按全额算，再补一次就是重复付款
        return m == null || m.getFundsMode() == null || m.getFundsMode().isBlank()
                ? FUNDS_AGGREGATED : m.getFundsMode();
    }

    @Override
    public String marketOf(String merchantNo) {
        MchEntity m = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getEntityNo, merchantNo).last("LIMIT 1")));
        // 查不到给 null，**不兜成 CN**：兜了之后「这家还没建」与「这家在大陆」
        // 在调用方看来一样，而前者该报错、后者该正常出一份大陆的渠道列表
        return m == null || m.getMarket() == null || m.getMarket().isBlank()
                ? null : m.getMarket();
    }

    @Override
    public String legalFormOf(String merchantNo) {
        MchEntity m = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getEntityNo, merchantNo).last("LIMIT 1")));
        // 查不到给 null，**不回落成某一档**：回落等于替这家认领了一个形态，
        // 而它只有一个用途 —— 取费率。取错的表现是账目静默差几分钱
        return m == null || m.getLegalForm() == null || m.getLegalForm().isBlank()
                ? null : m.getLegalForm();
    }

    @Override
    public FundRiskFacts fundRiskFacts(String merchantNo) {
        long deposit = 0L;
        long debt = 0L;
        try {
            var admission = admissionServiceProvider.getIfAvailable();
            deposit = admission == null ? 0L
                    : Math.max(admission.deposit(merchantNo).availableMinor(), 0L);
        } catch (RuntimeException e) {
            // 没有保证金账户是常态（大多数商家没缴过），不是错误 —— 按 0 算
            deposit = 0L;
        }
        try {
            var debtSvc = debtServiceProvider.getIfAvailable();
            debt = debtSvc == null ? 0L : debtSvc.balanceOf(merchantNo);
        } catch (RuntimeException e) {
            debt = 0L;
        }
        return new FundRiskFacts(deposit, debt);
    }

    @Override
    public String settleCycleOf(String merchantNo, String storeNo, String payChannel) {
        MchPaymentMerchant row = paymentOf(merchantNo, storeNo, payChannel);
        return row == null || row.getSettleCycle() == null || row.getSettleCycle().isBlank()
                ? null : row.getSettleCycle();
    }

    @Override
    public String feeBearerOf(String merchantNo, String storeNo, String payChannel) {
        MchPaymentMerchant row = paymentOf(merchantNo, storeNo, payChannel);
        return row == null ? null : row.getFeeBearer();
    }

    /**
     * 按 (主体, 门店, <b>通道</b>) 取进件档案，门店级优先、回落主体级。
     *
     * <p>自己查而不复用 {@code resolvePayment}：<b>那个方法不按通道筛</b>。
     * 单通道时代它每个 (主体,门店) 只对应一行，现在一家可以同时开微信和支付宝，
     * 不带通道条件会随机拿到其中一行 —— 而两个通道的费率承担方与账期本来就可以不同。
     */
    private MchPaymentMerchant paymentOf(String merchantNo, String storeNo, String payChannel) {
        return DataScopeContext.executeWithoutScope(() -> {
            if (storeNo != null && !storeNo.isBlank()) {
                MchPaymentMerchant own = merchantPaymentMapper.selectOne(
                        Wrappers.<MchPaymentMerchant>lambdaQuery()
                                .eq(MchPaymentMerchant::getEntityNo, merchantNo)
                                .eq(MchPaymentMerchant::getStoreNo, storeNo)
                                .eq(MchPaymentMerchant::getPayChannel, payChannel)
                                .last("LIMIT 1"));
                if (own != null) {
                    return own;
                }
            }
            return merchantPaymentMapper.selectOne(Wrappers.<MchPaymentMerchant>lambdaQuery()
                    .eq(MchPaymentMerchant::getEntityNo, merchantNo)
                    .eq(MchPaymentMerchant::getStoreNo, MchPaymentMerchant.ENTITY_LEVEL)
                    .eq(MchPaymentMerchant::getPayChannel, payChannel)
                    .last("LIMIT 1"));
        });
    }

    @Override
    @Transactional
    public int saveQualifications(String merchantNo, java.util.List<QualificationItem> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        // 已有的按 (类型, 证号) 建索引 —— 审核接口会被重复点击，
        // 重复写入会让「这家店有几张执照」变成一个假数字，而没有任何一处会报错
        var existing = DataScopeContext.executeWithoutScope(() ->
                        qualificationMapper.selectList(Wrappers.<MchQualification>lambdaQuery()
                                .eq(MchQualification::getEntityNo, merchantNo)))
                .stream()
                .map(q -> key(q.getQualType(), q.getQualNumber()))
                .collect(java.util.stream.Collectors.toSet());

        int added = 0;
        for (QualificationItem it : items) {
            if (it == null || it.type() == null || it.type().isBlank()) {
                // 类型为空的条目直接跳过：写进去也匹配不到任何授权码要求的资质，
                // 是一条永远不会生效的记录 —— 静默存下比拒绝更糟
                continue;
            }
            if (!existing.add(key(it.type(), it.code()))) {
                continue;
            }
            governService.saveQualification(merchantNo,
                    new ai.neargo.shop.merchant.service.MerchantGovernService.SaveQualificationCommand(
                            null, it.type(), qualNameOf(it.type()), it.code(),
                            it.imageUrl(), it.expireAt()),
                    "SYSTEM");
            added++;
        }
        return added;
    }

    private static String key(String type, String number) {
        return type + "|" + (number == null ? "" : number);
    }

    /**
     * 资质展示名。
     *
     * <p><b>必须与 {@code sys_auth_code.required_qualification} 同一套字面量</b> ——
     * 类目授权是拿证件名做字符串比对的，名字对不上就等于这张证不存在，
     * 而两边都不会报错。
     */
    private static String qualNameOf(String type) {
        return switch (type) {
            case MchQualification.BUSINESS_LICENSE -> "营业执照";
            case MchQualification.FOOD_PERMIT -> "食品经营许可证";
            case MchQualification.FOOD_WORKSHOP -> "食品小作坊登记证";
            default -> type;
        };
    }

    @Override
    @Transactional
    public String activate(ActivateCommand cmd) {
        String ownerUserNo = cmd.ownerUserNo();
        String name = cmd.name();
        String type = cmd.subject();

        /*
         * scope=COMMUNITY 却一个社区都没给 —— **直接拒绝，不要「先建了再说」**。
         * 建出来的结果是商家上着架却一个订单都不来，而这个故障没有任何报错，
         * 商家和运营都查不出原因（ADR-009）。宁可审核这一步失败，也不要产出一个隐形商家。
         */
        boolean byCommunity = cmd.serviceScope() == null || "COMMUNITY".equals(cmd.serviceScope());
        if (byCommunity && (cmd.communityNos() == null || cmd.communityNos().isEmpty())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        /*
         * 重复点击「通过」是常态，要幂等 —— 但**判据是这份申请，不是这个人**。
         *
         * 曾经按 owner_user_no 判重：老板申请第二张执照、审核通过时被当成重复点击，
         * 系统去改第一个主体，名称/行业/法律形态被覆盖，两家店变一家，且没有任何报错。
         * 按申请单判之后，同一个人申请几张执照互不干扰。
         */
        MchEntity existing = cmd.activatedEntityNo() == null ? null
                : DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getEntityNo, cmd.activatedEntityNo()).last("limit 1")));
        if (existing != null) {
            /*
             * **占位主体的「补证照」升级**，与「重复点通过」的幂等重放共用这一支。
             *
             * 两者的差别只在这里：占位主体是快速开店建的，身上有三样东西还空着 ——
             *   name        当时填的是门店名，现在要换成执照上的正式名称
             *   legal_form  当时不知道是个体户还是公司，进件那一步要用它
             *   verified    「已认证」标是审核给的，自己开店不能带
             * 而幂等重放的那份主体这三样早就对了。
             *
             * **只在升级时覆盖**，不能无条件写：重复点通过时无条件覆盖 name，
             * 会把商家后来在 B 端改过的店名冲回申请时填的那一版 ——
             * 与 applyProfile 用「非空才覆盖」防的是同一件事。
             */
            if (MchEntity.PENDING_LICENSE.equals(existing.getStatus())) {
                existing.setName(name);
                String canonical = masterDataPort.canonicalSubject(type);
                existing.setLegalForm(canonical != null ? canonical : type);
                existing.setVerified(true);
            }
            existing.setStatus(ACTIVE);
            existing.setServiceScope(cmd.serviceScope());
            applyProfile(existing, cmd);
            merchantMapper.updateById(existing);
            // 重复点击「通过」是常态：全部按幂等写，不重复插
            ensureOwnerStaff(existing.getEntityNo(), ownerUserNo);
            ensureDefaultStore(existing.getEntityNo(), name);
            ensureFreePlan(existing.getEntityNo());
            syncCommunities(existing.getEntityNo(), cmd.communityNos());
            ensurePayment(existing.getEntityNo(), type, cmd.settleAccountType());
            /*
             * 补证照通过 = 主体转 ACTIVE。无证照期间录好、上架的货，买家侧在查询时按主体状态现算，
             * 通过之后紧接着的那一次请求就看得到（门店与证照-产品方案「补证照审核通过后，同一批商品立刻对买家可见」）。
             */
            return existing.getEntityNo();
        }

        requireEntityQuota(ownerUserNo);

        MchEntity m = new MchEntity();
        m.setEntityNo(BizKey.next(BizKey.MERCHANT));
        m.setName(name);
        m.setLogo("");
        /*
         * **写入一律用权威码**（ADR-010 §4 第 2 步）。
         * 认不出来的取值原样保留而不是兜底成某个值 —— 兜底会把"数据脏了"
         * 悄悄变成"数据是干净的但值不对"，后者查不出来。
         */
        String canonical = masterDataPort.canonicalSubject(type);
        m.setLegalForm(canonical != null ? canonical : type);
        m.setOwnerUserNo(ownerUserNo);
        // 新店从中位分起步，不是 0 分 —— 0 分会让新店在任何按评分排的列表里垫底，
        // 而它还没有任何订单可以证明自己（ADR-009 里「平台推荐位」要解决的也是这个问题）
        m.setRating(RATING_INIT);
        m.setRatingCount(0);
        m.setSalesCount(0);
        m.setGoodsCount(0);
        m.setScoreGoods(RATING_INIT);
        m.setScoreService(RATING_INIT);
        m.setScoreSpeed(RATING_INIT);
        m.setVerified(true);   // 审核通过即带认证标
        m.setBreachCount(0);
        m.setTags("[]");
        applyProfile(m, cmd);
        m.setJoinedAt(System.currentTimeMillis());
        m.setStatus(ACTIVE);
        m.setServiceScope(cmd.serviceScope() == null ? "COMMUNITY" : cmd.serviceScope());
        merchantMapper.insert(m);

        /*
         * 这几件事与建商家**必须同一个事务** —— 少任何一件，商家就是「存在但做不了生意」：
         *   成员行  少了他登录后没有 B 端身份（M1 起身份来源是 mch_account）
         *   默认门店 少了他没有可经营的门店
         *   覆盖范围 少了他对谁都不可见（ADR-009）
         *   分账主体 少了第一笔订单就分不了账（ADR-002）
         */
        ensureOwnerStaff(m.getEntityNo(), ownerUserNo);
        ensureDefaultStore(m.getEntityNo(), name);
        ensureFreePlan(m.getEntityNo());
        syncCommunities(m.getEntityNo(), cmd.communityNos());
        ensurePayment(m.getEntityNo(), type, cmd.settleAccountType());
        return m.getEntityNo();
    }

    @Override
    @Transactional
    public QuickStartResult quickStart(QuickStartCommand cmd) {
        String ownerUserNo = cmd.ownerUserNo();
        if (ownerUserNo == null || ownerUserNo.isBlank()
                || cmd.storeName() == null || cmd.storeName().isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        /*
         * **一个账号最多一个待补证照的占位主体**：已经有就原样返回它。
         *
         * 这一条同时兜住两件事：连点两次不会建出两个空壳；账号里也不会堆出一串
         * 永远补不齐的占位主体。想在这个占位主体下再开一家店，走正常建店接口
         * （那时 BizContext 已经有了，不需要走这条路）。
         */
        var shell = pendingLicenseEntityOf(ownerUserNo);
        if (shell.isPresent()) {
            return new QuickStartResult(shell.get(), defaultStoreNoOf(shell.get()));
        }

        requireEntityQuota(ownerUserNo);

        String name = cmd.storeName().trim();
        MchEntity m = new MchEntity();
        m.setEntityNo(BizKey.next(BizKey.MERCHANT));
        m.setName(name);
        m.setLogo("");
        /*
         * 证照还没交，所以这几项与 activate() 刻意不同：
         *   legal_form  留空 —— 还不知道是个体户还是公司，进件那一步才需要它
         *   verified    false —— 「已认证」标是审核给的，不能自己开店就带上
         *   status      PENDING_LICENSE —— 可见性闸门认的就是它
         * 其余（评分初值、计数器）与 activate() 保持一致，避免两条路建出来的主体
         * 在别处表现不同。
         */
        m.setRating(RATING_INIT);
        m.setRatingCount(0);
        m.setSalesCount(0);
        m.setGoodsCount(0);
        m.setScoreGoods(RATING_INIT);
        m.setScoreService(RATING_INIT);
        m.setScoreSpeed(RATING_INIT);
        m.setVerified(false);
        m.setBreachCount(0);
        m.setTags("[]");
        m.setOwnerUserNo(ownerUserNo);
        m.setJoinedAt(System.currentTimeMillis());
        m.setStatus(MchEntity.PENDING_LICENSE);
        m.setServiceScope(AREA_COMMUNITY);
        merchantMapper.insert(m);

        /*
         * 与 activate() 同样是一个事务，但**少两件**：
         *   覆盖范围 不配 —— 反正对谁都不可见，等他补证照时在申请单里一起填
         *   分账主体 不建 —— 没证照没法进件，建个空占位只会让「收款设置」页
         *                   显示一条永远推不动的记录
         */
        ensureOwnerStaff(m.getEntityNo(), ownerUserNo);
        ensureDefaultStore(m.getEntityNo(), name, cmd.address());
        ensureFreePlan(m.getEntityNo());
        /*
         * 门店号一并返回。调用方拿它当 X-Store-No 进这家新店 —— 身份解析按门店反查主体，
         * 而这个账号名下可能已经有另一张执照（多证照），按默认主体解析出来的是**旧的那家**。
         */
        return new QuickStartResult(m.getEntityNo(), defaultStoreNoOf(m.getEntityNo()));
    }

    /** 主体的默认门店号。解除数据域的理由同 {@link #ensureDefaultStore} —— 建主体时作用域还不存在。 */
    private String defaultStoreNoOf(String merchantNo) {
        var store = DataScopeContext.executeWithoutScope(() ->
                storeMapper.selectOne(Wrappers.<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchStore::getEntityNo, merchantNo)
                        .eq(ai.neargo.shop.merchant.entity.MchStore::getIsDefault, true)
                        .last("limit 1")));
        return store == null ? null : store.getStoreNo();
    }

    @Override
    public java.util.Optional<String> pendingLicenseEntityOf(String userNo) {
        if (userNo == null || userNo.isBlank()) {
            return java.util.Optional.empty();
        }
        List<String> ownedEntityNos = DataScopeContext.executeWithoutScope(() ->
                staffMapper.selectList(Wrappers.<ai.neargo.shop.merchant.entity.MchAccount>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchAccount::getUserNo, userNo)
                        .eq(ai.neargo.shop.merchant.entity.MchAccount::getIsOwner, true))
                .stream().map(ai.neargo.shop.merchant.entity.MchAccount::getEntityNo).toList());
        if (ownedEntityNos.isEmpty()) {
            return java.util.Optional.empty();
        }
        MchEntity shell = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                        .in(MchEntity::getEntityNo, ownedEntityNos)
                        .eq(MchEntity::getStatus, MchEntity.PENDING_LICENSE)
                        .last("limit 1")));
        return java.util.Optional.ofNullable(shell).map(MchEntity::getEntityNo);
    }

    /**
     * 建新主体前的数量闸。
     *
     * <p><b>两条建主体的路都要过它</b>：快速开店（{@link #quickStart}）与
     * 审核通过（{@link #activate} 的新建分支）。只拦一条的话，另一条就是绕过去的口子。
     *
     * <p>只数 owner 行 —— 被邀请去别人店里当店员不占自己的名额，
     * 那不是他的证照。
     */
    private void requireEntityQuota(String ownerUserNo) {
        if (ownerUserNo == null || ownerUserNo.isBlank()) {
            return;
        }
        long owned = DataScopeContext.executeWithoutScope(() ->
                staffMapper.selectList(Wrappers.<ai.neargo.shop.merchant.entity.MchAccount>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchAccount::getUserNo, ownerUserNo)
                        .eq(ai.neargo.shop.merchant.entity.MchAccount::getIsOwner, true))
                .stream().map(ai.neargo.shop.merchant.entity.MchAccount::getEntityNo)
                .distinct().count());
        if (owned >= MAX_ENTITIES_PER_ACCOUNT) {
            throw BizException.of(ErrorCode.ENTITY_QUOTA_EXCEEDED, MAX_ENTITIES_PER_ACCOUNT);
        }
    }

    /**
     * 建 owner 成员行。<b>身份来源</b>（M1 起取代 {@code owner_user_no}）。
     *
     * <p>漏掉它的后果最隐蔽：商家审核通过、`mch_entity` 有行、C 端也搜得到这家店，
     * 唯独他自己登录 B 端时 {@code BizContext} 是空的 —— 所有 /biz/** 都 403，
     * 而他看到的只是「打不开」。
     */
    private void ensureOwnerStaff(String merchantNo, String ownerUserNo) {
        if (ownerUserNo == null || ownerUserNo.isBlank()) {
            return;
        }
        boolean exists = DataScopeContext.executeWithoutScope(() ->
                staffMapper.exists(Wrappers.<ai.neargo.shop.merchant.entity.MchAccount>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchAccount::getEntityNo, merchantNo)
                        .eq(ai.neargo.shop.merchant.entity.MchAccount::getUserNo, ownerUserNo)));
        if (exists) {
            return;
        }
        var staff = new ai.neargo.shop.merchant.entity.MchAccount();
        staff.setMchAccountNo(BizKey.next(BizKey.MERCHANT_STAFF));
        staff.setEntityNo(merchantNo);
        staff.setUserNo(ownerUserNo);
        staff.setIsOwner(true);
        // 第一个主体自动成为默认；已有主体时不抢默认 —— 那是用户自己的选择
        boolean hasPrimary = DataScopeContext.executeWithoutScope(() ->
                staffMapper.exists(Wrappers.<ai.neargo.shop.merchant.entity.MchAccount>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchAccount::getUserNo, ownerUserNo)
                        .eq(ai.neargo.shop.merchant.entity.MchAccount::getIsPrimary, true)));
        staff.setIsPrimary(!hasPrimary);
        staff.setStatus(ai.neargo.shop.merchant.entity.MchAccount.ACTIVE);
        DataScopeContext.executeWithoutScope(() -> staffMapper.insert(staff));
    }

    /**
     * 建 FREE 订阅行（V150）。
     *
     * <p><b>与 {@link #ensureDefaultStore} 是同一类动作</b>：主体一激活就该有的东西。
     * 迁移里的回填只覆盖迁移那一刻的存量，**新入驻的商家没人给它建行** ——
     * 漏了这一步的症状不是报错，是额度一路落到配置兜底：
     * 测试环境那个兜底是 3，于是新商家能开三家店，而他明明是 FREE。
     * （实测抓到：三条额度用例同时红。）
     */
    private void ensureFreePlan(String merchantNo) {
        boolean exists = DataScopeContext.executeWithoutScope(() ->
                entityPlanMapper.exists(Wrappers.<ai.neargo.shop.merchant.entity.MchEntityPlan>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchEntityPlan::getEntityNo, merchantNo)));
        if (exists) {
            return;
        }
        var def = DataScopeContext.executeWithoutScope(() ->
                planDefMapper.selectOne(Wrappers.<ai.neargo.shop.merchant.entity.SysMerchantPlanDef>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.SysMerchantPlanDef::getPlanCode,
                                ai.neargo.shop.merchant.entity.MchEntityPlan.FREE)
                        .last("limit 1")));
        var row = new ai.neargo.shop.merchant.entity.MchEntityPlan();
        row.setEntityNo(merchantNo);
        row.setPlanCode(ai.neargo.shop.merchant.entity.MchEntityPlan.FREE);
        // 档位定义缺失时给 1/0 —— 与 FREE 的种子逐字一致，不去读那个会被改的配置
        row.setStoreQuota(def == null || def.getStoreQuota() == null ? 1 : def.getStoreQuota());
        row.setStaffQuota(def == null || def.getStaffQuota() == null ? 0 : def.getStaffQuota());
        row.setCrossStoreStats(def != null && Boolean.TRUE.equals(def.getCrossStoreStats()));
        row.setStatus(ai.neargo.shop.merchant.entity.MchEntityPlan.ACTIVE);
        row.setGrantedBy(ai.neargo.shop.merchant.entity.MchEntityPlan.BY_SELF_PAID);
        row.setTrialUsed(false);
        DataScopeContext.executeWithoutScope(() -> entityPlanMapper.insert(row));
    }

    /** 建默认门店。一主体恰好一个，删不掉 —— 它是单店商家的全部。 */
    private void ensureDefaultStore(String merchantNo, String name) {
        ensureDefaultStore(merchantNo, name, null);
    }

    /** @param address 门店地址，可空 —— 走审核那条路时地址在店铺资料里单独填，只有快速开店会带进来 */
    private void ensureDefaultStore(String merchantNo, String name, String address) {
        boolean exists = DataScopeContext.executeWithoutScope(() ->
                storeMapper.exists(Wrappers.<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchStore::getEntityNo, merchantNo)));
        if (exists) {
            return;
        }
        var store = new ai.neargo.shop.merchant.entity.MchStore();
        store.setStoreNo(BizKey.next(BizKey.STORE));
        store.setEntityNo(merchantNo);
        store.setName(name);
        if (address != null && !address.isBlank()) {
            store.setAddress(address.trim());
        }
        store.setIsDefault(true);
        store.setStatus(ai.neargo.shop.merchant.entity.MchStore.ACTIVE);
        store.setFeatured("[]");
        DataScopeContext.executeWithoutScope(() -> storeMapper.insert(store));
    }

    /**
     * 把申请单上的行业与简介落到商家主体。
     *
     * <p>此前这两项<b>只存在申请单上</b>：
     * <ul>
     *   <li>{@code industry} 决定这家店能不能开小微（微信白名单按行业给）。
     *       商家主体上永远是空的话，进件那一刻才发现主体选错了，而入驻早就通过了；
     *       它还是 {@code points_forced} 默认值的来源 —— 那个字段的注释写着
     *       「按行业强制开」，而行业不存在，那句话一直无法执行。</li>
     *   <li>{@code description} 是 C 端门店页展示的店铺简介。商家认真写的一段话
     *       通过审核后就没了 —— 不报错，只是门店页少一块。</li>
     * </ul>
     *
     * <p>用「非空才覆盖」而不是无条件赋值：重复点通过时，
     * 不该把商家后来在 B 端改过的简介冲回申请时填的那一版。
     */
    private void applyProfile(MchEntity m, ActivateCommand cmd) {
        if (cmd.industry() != null && !cmd.industry().isBlank()) {
            m.setIndustry(cmd.industry());
        }
        if (cmd.description() != null && !cmd.description().isBlank()) {
            m.setDescription(cmd.description());
        }
    }

    /**
     * 覆盖社区。<b>委托给 {@link ai.neargo.shop.merchant.service.MerchantStoreService}</b> ——
     * 店铺设置页也在改同一件事，两处各写一份迟早对不上，
     * 而对不上的后果是「入驻时配好的范围被店铺设置悄悄清空」。
     */
    private void syncCommunities(String merchantNo, List<String> communityNos) {
        merchantStoreService.syncCommunities(merchantNo, communityNos);
    }

    /**
     * 建分账主体占位记录（ADR-002）。
     *
     * <p>此刻多半还没有真实的开户结果 —— 那要走支付服务商的流程。但记录要先建出来：
     * 否则第一笔订单来了才发现<b>没有收款方</b>，钱已经收了却分不出去。
     * 状态置 APPLYING，商家在 B 端补完资料后推进。
     */
    private void ensurePayment(String merchantNo, String subject, String settleAccountType) {
        boolean exists = DataScopeContext.executeWithoutScope(() ->
                merchantPaymentMapper.exists(Wrappers.<MchPaymentMerchant>lambdaQuery()
                        .eq(MchPaymentMerchant::getEntityNo, merchantNo)));
        if (exists) {
            return;
        }
        MchPaymentMerchant p = new MchPaymentMerchant();
        p.setEntityNo(merchantNo);
        p.setPayChannel(MchPaymentMerchant.WECHAT);
        /*
         * 主体 → 通道进件主体，走主数据而不是在这里再写一遍 if。
         * 这是「PERSONAL 是不是就是小微」的**第三处**实现 —— 前两处在建商家与入驻校验。
         * 三处各写各的，判错一次商家就是进件被拒（ADR-002 §4 / ADR-010）。
         */
        String canonical = masterDataPort.canonicalSubject(subject);
        p.setLegalForm(canonical != null ? canonical : MchPaymentMerchant.INDIVIDUAL);
        p.setApplyStatus(MchPaymentMerchant.APPLYING);
        // 结算账户形态也由主体决定：小微打到个人，其余打到对公。
        // 申请时没填就按主体的默认形态 —— 让商家去猜"该填哪个"是没道理的
        p.setSettleAccountType(settleAccountType != null && !settleAccountType.isBlank()
                ? settleAccountType : masterDataPort.settleAccountType(canonical));
        p.setAppliedAt(System.currentTimeMillis());
        DataScopeContext.executeWithoutScope(() -> merchantPaymentMapper.insert(p));
    }

    /**
     * 四级串联：全局 → 社区 → 主体非小微 → 本店开关。
     *
     * <p><b>顺序是有语义的</b>：主体这一级必须排在商家开关之前 ——
     * 小微是「不可开」不是「关着」。顺序反了的话小微商家会看到
     * 「本店未开启积分」，以为自己打开就行，而他打不开。
     */
    @Override
    public String pointsDenyReason(String merchantNo) {
        MchEntity m = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(
                Wrappers.<MchEntity>lambdaQuery().eq(MchEntity::getEntityNo, merchantNo).last("LIMIT 1")));
        if (m == null) {
            return "商家不存在";
        }

        // L2 社区：上层关，下层一定关。
        // 商家可覆盖多个社区（ADR-009 三档范围），**一个开着就算开** ——
        // 判否要求全部关闭，否则跨社区经营的商家会因为某个未开放的社区被整体禁掉
        List<String> reachable = reachableCommunities(merchantNo);
        if (!reachable.isEmpty()) {
            boolean anyOpen = communityQueryPort.anyPointsEnabled(reachable);
            if (!anyOpen) {
                return "本社区暂未开放积分";
            }
        }

        /*
         * 主体：**无照商户能不能开积分，取决于资金路径**。
         *
         * ┌ 直连（钱在商家二级户）
         * │   积分抵扣让他少收 → 平台必须补差进去 → **那是一次平台付钱给自然人**
         * │   → 扣缴义务定性模糊 → 维持禁止
         * └ 归集（钱在平台户）
         *     平台自己少收，没有「补」这个动作；付给他的是货款，
         *     且农产品场景下平台自开收购发票 → **可以开**
         *
         * ⚠️ 此前这里判的是 `mch_payment_merchant.legalForm == MICRO` —— 两处都不对：
         *   1. 判据选错了轴。要不要补差看**钱在谁手里**（funds_mode），
         *      不是「他是什么主体」，更不是「谁是销售主体」（business_mode）。
         *   2. 读错了字段。那一列是**通道进件档**（微信小微/个体户），
         *      不是主体的法律形态 —— 通道给他开了小微户，不代表他就是无照。
         *
         * 现在：法律形态走注册表（needLicense），路径走 funds_mode。
         */
        boolean unlicensed = !masterDataPort.needLicense(m.getLegalForm());
        if (unlicensed && FUNDS_DIRECT.equals(fundsModeOf(merchantNo))) {
            return "本店暂不支持积分（无营业执照，且收款直连到商家账户）";
        }

        // L3 本店。forced 为真时商家关不掉
        if (Boolean.FALSE.equals(m.getPointsEnabled()) && !Boolean.TRUE.equals(m.getPointsForced())) {
            return "本店未开启积分";
        }
        return null;
    }

    @Override
    public boolean isPointsForced(String merchantNo) {
        MchEntity m = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(
                Wrappers.<MchEntity>lambdaQuery().eq(MchEntity::getEntityNo, merchantNo).last("LIMIT 1")));
        return m != null && Boolean.TRUE.equals(m.getPointsForced());
    }

        @Override
    public java.util.Set<String> activeChannelsOf(String merchantNo, String storeNo) {
        return DataScopeContext.executeWithoutScope(() ->
                merchantPaymentMapper.selectList(Wrappers.<MchPaymentMerchant>lambdaQuery()
                        .eq(MchPaymentMerchant::getEntityNo, merchantNo)
                        .eq(MchPaymentMerchant::getApplyStatus, MchPaymentMerchant.ACTIVE)))
                .stream()
                /*
                 * 门店级进件只在**问的就是那家店**时算数；主体级（store_no 为空）
                 * 对所有门店都算数。反过来（拿别家店的进件当自己的）
                 * 会让钱进到另一家店的收款号。
                 */
                .filter(r -> r.getStoreNo() == null || r.getStoreNo().isEmpty()
                        || r.getStoreNo().equals(storeNo))
                .map(MchPaymentMerchant::getPayChannel)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Override
    public PayCapability payCapabilityOf(String merchantNo, String storeNo) {
        MchPaymentMerchant pm = resolvePayment(merchantNo, storeNo);
        if (pm == null) {
            /*
             * 进件还没走完的商家：全放行。
             *
             * 拦下来的话，一个还在审核中的商家会表现为「他的货谁都买不了」，
             * 而真实情况是钱先欠着、进件完成后再打 —— 那是结算的事，不是成交的事。
             */
            return new PayCapability(java.util.Set.of(), true, 0L, 0L);
        }
        /*
         * ★ 能不能开票，判的是「**谁是销售主体**」，不是「这家商家自己开不开得出票」。
         *
         * 归集路径下平台是销售主体：合同相对方是平台、钱在平台账户，
         * **票由平台开给消费者**（ADR-017 §3.4 条件 2）——
         * 供应商有没有票是平台跟他之间的事（进项），与消费者这张销项票无关。
         *
         * 此前只读 mch_payment_merchant.invoice_capable，于是无照自然人在归集下
         * 会显示「本商家无法开具发票」。那句话有两重错：
         * 一是事实错（平台开得出），二是**它把销售方指给了商家** ——
         * 而那正是 seller-statement 守卫在防的表述（写了它，归集资金模式就不成立）。
         *
         * 与积分判据同一根轴：**责任跟着钱走**。
         */
        /*
         * ⚠️ **不能用 fundsModeOf()** —— 它查不到主体时默认归集。
         * 那个默认在补差那条轴上是安全的（宁可不补，也不能重复付款），
         * 在这条轴上却是反的：**「查不到主体」不等于「平台是销售主体」**，
         * 照那个默认走，会对一个连主体都找不到的商家承诺开票。
         *
         * 同一个默认值在两条轴上的安全方向相反 —— 所以这里直接读实体。
         */
        MchEntity entity = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getEntityNo, merchantNo).last("LIMIT 1")));
        boolean platformIsSeller = entity != null
                && MerchantQueryPort.FUNDS_AGGREGATED.equals(entity.getFundsMode());
        return new PayCapability(
                readList(pm.getPayMethods()),
                platformIsSeller || !Boolean.FALSE.equals(pm.getInvoiceCapable()),
                pm.getQuotaLimitMinor() == null ? 0L : pm.getQuotaLimitMinor(),
                pm.getQuotaUsedMinor() == null ? 0L : pm.getQuotaUsedMinor());
    }

    /**
     * 评分整份盖掉，不做增量 —— 口径见 {@link ai.neargo.shop.spi.user.MerchantRatingPort}。
     */
    @Override
    @Transactional
    public void updateRating(String merchantNo, ai.neargo.shop.spi.user.MerchantRatingPort.Rating r) {
        MchEntity m = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getEntityNo, merchantNo).last("limit 1")));
        if (m == null) {
            // 商家不存在不该让「发表评价」整笔失败：评价本身是有效的
            return;
        }
        /*
         * **一条评价都没有时回到中位分，而不是 0 分** —— 与 {@code activate()} 同一条规矩：
         * 0 分会让这家店在任何按评分排的列表里垫底，而它还没有任何订单可以证明自己。
         * 这条路径不只发生在新店：唯一那条评价被平台驳回、或申诉成立撤下之后，
         * 商家会退回「还没人评过」的状态，那时也该退回中位分，不能因为一条被裁掉的
         * 差评把他打到底。
         *
         * 展示层不看这个数：端上按 `ratingCount == 0` 显示「暂无评价」，
         * 所以中位分只影响排序，不会在页面上冒充一个 5.0。
         */
        boolean rated = r.count() > 0;
        m.setRating(rated ? r.ratingX10() : RATING_INIT);
        m.setRatingCount(r.count());
        // 三维度与综合分同源同一批评价：分开写会让看板与总分对不上，而没人看得出是哪边错
        m.setScoreGoods(rated ? r.goodsX10() : RATING_INIT);
        m.setScoreService(rated ? r.serviceX10() : RATING_INIT);
        m.setScoreSpeed(rated ? r.speedX10() : RATING_INIT);
        DataScopeContext.executeWithoutScope(() -> merchantMapper.updateById(m));
    }

    @Override
    public void updateStoreRating(String storeNo, ai.neargo.shop.spi.user.MerchantRatingPort.Rating r) {
        var st = DataScopeContext.executeWithoutScope(() ->
                storeMapper.selectOne(Wrappers.<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchStore::getStoreNo, storeNo)
                        .last("limit 1")));
        if (st == null) {
            // 门店不存在不该让「发表评价」整笔失败 —— 与主体那条同一条规矩
            return;
        }
        /*
         * 与主体评分逐字同构，包括「一条评价都没有时回到中位分而不是 0 分」：
         * 0 分会让这家店在任何按评分排的列表里垫底，而它还没有任何订单可以证明自己。
         * 展示层按 ratingCount == 0 显示「暂无评价」，所以中位分只影响排序。
         *
         * ⚠️ **老评价（store_no 为空）不会走到这里**（调用方只在评价带门店时调）——
         * 于是一家老店在第一条带门店的新评价到来之前，rating_count 是 0。
         * 那是对的：把主体分照搬给每家店，等于让新开的分店凭空继承老店的口碑。
         */
        boolean rated = r.count() > 0;
        st.setRating(rated ? r.ratingX10() : RATING_INIT);
        st.setRatingCount(r.count());
        st.setScoreGoods(rated ? r.goodsX10() : RATING_INIT);
        st.setScoreService(rated ? r.serviceX10() : RATING_INIT);
        st.setScoreSpeed(rated ? r.speedX10() : RATING_INIT);
        DataScopeContext.executeWithoutScope(() -> storeMapper.updateById(st));
    }

    @Override
    @Transactional
    public void accruePayQuota(String merchantNo, String storeNo, long amountMinor) {
        if (amountMinor <= 0) {
            return;
        }
        MchPaymentMerchant pm = resolvePayment(merchantNo, storeNo);
        if (pm == null) {
            // 进件还没走完：没有额度可记，也不该因此让支付回调失败
            return;
        }
        String period = currentQuotaPeriod();
        if (!period.equals(pm.getQuotaPeriod())) {
            /*
             * 周期翻篇：清零重算。
             *
             * 周期由这里按当前时间算而不是让调用方传 —— 传进来的话，
             * 补发的历史回调会把去年的钱记进今年的额度里。
             */
            pm.setQuotaPeriod(period);
            pm.setQuotaUsedMinor(0L);
        }
        pm.setQuotaUsedMinor((pm.getQuotaUsedMinor() == null ? 0L : pm.getQuotaUsedMinor()) + amountMinor);
        merchantPaymentMapper.updateById(pm);
    }

    /**
     * 当前额度统计周期。
     *
     * <p><b>按自然年</b>——微信对小微的额度口径是年累计。
     * 这个口径要由服务商确认；改口径只改这一个方法，
     * 而已落库的 {@code quota_period} 会让翻篇自动发生。
     */
    private String currentQuotaPeriod() {
        return String.valueOf(java.time.LocalDate.now().getYear());
    }

    /** 本店专属收款记录优先，回落到主体默认号 —— 不配店号就是「合并结算，走主体号」。 */
    private MchPaymentMerchant resolvePayment(String merchantNo, String storeNo) {
        if (storeNo != null && !storeNo.isBlank()) {
            MchPaymentMerchant own = DataScopeContext.executeWithoutScope(() ->
                    merchantPaymentMapper.selectOne(
                    Wrappers.<MchPaymentMerchant>lambdaQuery()
                            .eq(MchPaymentMerchant::getEntityNo, merchantNo)
                            .eq(MchPaymentMerchant::getStoreNo, storeNo)
                            .last("LIMIT 1")));
            if (own != null) {
                return own;
            }
        }
        return merchantPaymentMapper.selectOne(Wrappers.<MchPaymentMerchant>lambdaQuery()
                .eq(MchPaymentMerchant::getEntityNo, merchantNo)
                .eq(MchPaymentMerchant::getStoreNo, MchPaymentMerchant.ENTITY_LEVEL)
                .last("LIMIT 1"));
    }

    private java.util.Set<String> readList(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return java.util.Set.of();
        }
        try {
            return new java.util.HashSet<>(json.readValue(rawJson,
                    new tools.jackson.core.type.TypeReference<java.util.List<String>>() {
                    }));
        } catch (RuntimeException e) {
            // 与 authorizedCategoryCodes 同向：坏 JSON 按「什么都不支持」处理，
            // 让调用方看见空集合并提示，而不是当成「全都支持」放过去
            return java.util.Set.of();
        }
    }

    @Override
    public java.util.Optional<String> ownerUserNoOf(String merchantNo) {
        MchEntity m = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(
                Wrappers.<MchEntity>lambdaQuery().eq(MchEntity::getEntityNo, merchantNo).last("LIMIT 1")));
        return m == null ? java.util.Optional.empty()
                : java.util.Optional.ofNullable(m.getOwnerUserNo());
    }

@Override
    public boolean hasExpiredQualification(String merchantNo) {
        return governService.hasExpiredQualification(merchantNo);
    }

    @Override
    public java.util.Set<String> authorizedCategoryCodes(String merchantNo) {
        MchEntity m = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(
                Wrappers.<MchEntity>lambdaQuery().eq(MchEntity::getEntityNo, merchantNo).last("LIMIT 1")));
        if (m == null || m.getCategoryCodes() == null || m.getCategoryCodes().isBlank()) {
            // 空 = 没有任何特许类目，只能上架无门槛的类目。**不是「不限制」**
            return java.util.Set.of();
        }
        try {
            return new java.util.HashSet<>(json.readValue(m.getCategoryCodes(),
                    new tools.jackson.core.type.TypeReference<java.util.List<String>>() {
                    }));
        } catch (RuntimeException e) {
            /*
             * 脏数据一律按「没有授权」处理，而不是按「不限制」放行 ——
             * 解析失败时放行，等于一行坏 JSON 就能绕过整套准入校验，
             * 且没有任何症状。宁可让商家看到「没有资质」去申诉。
             */
            return java.util.Set.of();
        }
    }

    @Override
    public void setPointsEnabled(String merchantNo, boolean enabled) {
        MchEntity m = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(
                Wrappers.<MchEntity>lambdaQuery().eq(MchEntity::getEntityNo, merchantNo).last("LIMIT 1")));
        if (m == null) {
            return;
        }
        // 只改开关。**不动已发出的分，也不退已扣的服务费**
        m.setPointsEnabled(enabled);
        merchantMapper.updateById(m);
    }

    @Override
    public long countByIndustry(String industry) {
        return merchantMapper.selectCount(
                Wrappers.<MchEntity>lambdaQuery().eq(MchEntity::getIndustry, industry));
    }

    @Override
    public long countByServiceScope(String serviceScope) {
        return merchantMapper.selectCount(
                Wrappers.<MchEntity>lambdaQuery().eq(MchEntity::getServiceScope, serviceScope));
    }

    @Override
    public long countByAuthCode(String code) {
        /*
         * category_codes 是 JSON 数组存在 VARCHAR 里（V4 的取舍：H2 的 JSON 类型会
         * 多包一层引号，反序列化直接失败）。这里用 LIKE 匹配带引号的码，
         * 而不是裸的 code —— 裸匹配会让 FRESH_VEG 命中 FRESH_VEGETABLE 那种前缀重合的码，
         * 统计出来的影响面偏大，而偏大的影响面会让运营不敢动本该停用的码。
         */
        return merchantMapper.selectCount(Wrappers.<MchEntity>lambdaQuery()
                .like(MchEntity::getCategoryCodes, "\"" + code + "\""));
    }
}

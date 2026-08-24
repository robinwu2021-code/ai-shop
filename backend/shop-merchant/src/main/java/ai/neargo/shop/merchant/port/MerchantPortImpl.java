package ai.neargo.shop.merchant.port;

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
    private static final String PICKUP = "PICKUP";
    private static final String SHIPPING = "SHIPPING";
    private static final String AREA_ACTIVE = "ACTIVE";
    private static final String AREA_COMMUNITY = "COMMUNITY";
    /** 评分存整数（50 = 5.0 分），避免浮点入库 */
    private static final int RATING_SCALE = 10;
    private static final int RATING_INIT = 50;

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
    private final ai.neargo.shop.merchant.service.MerchantAuthCodeService authCodeService;
    private final tools.jackson.databind.ObjectMapper json;
    /** 主体激活时建 FREE 订阅行（V150）—— 与 ensureDefaultStore 同一类动作 */
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.EntityPlanMapper entityPlanMapper;
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.PlanDefMapper planDefMapper;
    /** 门店送货方式（方案 v4）—— 可见性与下单闸的取数口 */
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.FulfillmentChannelMapper fulfillmentChannelMapper;
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.ChannelPickupMapper channelPickupMapper;
    private final ai.neargo.shop.spi.user.PickupQueryPort pickupQueryPort;
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.ChannelAreaMapper channelAreaMapper;

    public MerchantPortImpl(MchEntityMapper merchantMapper, MchEntityCommunityMapper merchantCommunityMapper,
                            MchPaymentMapper merchantPaymentMapper,
                            ai.neargo.shop.merchant.service.MerchantStoreService merchantStoreService,
                            ai.neargo.shop.spi.user.CommunityQueryPort communityQueryPort,
                            ai.neargo.shop.spi.platform.MasterDataPort masterDataPort,
                            ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper staffMapper,
                            ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper storeMapper,
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
                            ai.neargo.shop.merchant.mapper.MerchantMappers.ChannelAreaMapper channelAreaMapper) {
        this.fulfillmentChannelMapper = fulfillmentChannelMapper;
        this.channelPickupMapper = channelPickupMapper;
        this.pickupQueryPort = pickupQueryPort;
        this.channelAreaMapper = channelAreaMapper;
        this.authCodeService = authCodeService;
        this.entityPlanMapper = entityPlanMapper;
        this.planDefMapper = planDefMapper;
        this.qualificationMapper = qualificationMapper;
        this.governService = governService;
        this.json = json;
        this.staffMapper = staffMapper;
        this.storeMapper = storeMapper;
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
        MchEntity m = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getEntityNo, merchantNo).last("limit 1")));
        if (m == null) {
            return List.of();
        }
        /*
         * ADR-013 阶段二：履约能力 × 地理覆盖，两者正交。
         *
         * **这个方法是可见性的唯一出口** —— 上架写社区池、商家详情可达性、履约都只认它。
         * 正因为当初收敛到了这一处，换模型才只用改这里，调用方一行不动。
         *
         * 方案 v4：履约能力从 fulfillment_reach 单值换成 channel 集合（主体级并集 ——
         * 可见性是主体级的，任何一家门店送得到就算可达）。语义映射保持迁移前后行为一致：
         *   EXPRESS ∈ 集合            → 原 SHIPPING：全部开放社区
         *   范围空 + MERCHANT_DELIVERY → 原 ONSITE 的「没框 = 不限」
         *   范围空 + 只有自提           → 原 PICKUP 的「没框 = 谁也看不到」
         * 集合为空 = 该主体还没迁移到 channel 模型，回落旧列 —— 只读兼容期的约定，
         * 删列那一版一并删掉这个回落。
         */
        java.util.Set<String> channels = enabledFulfillments(merchantNo, null);
        boolean expressOn;
        boolean deliveryOn;
        boolean legacy = channels.isEmpty();
        if (legacy) {
            String reach = m.getFulfillmentReach() == null ? PICKUP : m.getFulfillmentReach();
            expressOn = SHIPPING.equals(reach);
            deliveryOn = !PICKUP.equals(reach) && !SHIPPING.equals(reach);
        } else {
            expressOn = channels.contains(ai.neargo.shop.common.Fulfillments.EXPRESS);
            deliveryOn = channels.contains(ai.neargo.shop.common.Fulfillments.MERCHANT_DELIVERY);
        }

        // 快递没有履约半径，不该被要求逐个勾社区 —— 那既是无谓劳动，
        // 也会在新开城时漏掉（新社区不会自动出现在别人手工勾的清单里）
        if (expressOn) {
            return communityQueryPort.openCommunityNos();
        }

        List<MchServiceArea> areas = DataScopeContext.executeWithoutScope(() ->
                serviceAreaMapper.selectList(Wrappers.<MchServiceArea>lambdaQuery()
                        .eq(MchServiceArea::getEntityNo, merchantNo)
                        .eq(MchServiceArea::getStatus, AREA_ACTIVE)));

        if (areas.isEmpty()) {
            /*
             * **「没框范围」的含义由履约能力决定**（ADR-013 §6.2）——
             * 这是从三档枚举迁过来时保持行为不变的关键一格：
             *
             *   只有自提          没框就是没有落点 → 谁也看不到
             *                    （原 PICKUP / scope=COMMUNITY 却没配社区，写入口也一直拦着）
             *   开了商家自送      上门没有落点约束，没框 = 不限 → 全部开放社区
             *                    （原 ONSITE / scope=CITY 就是这个结果）
             *
             * 两者反过来都会出事：把自提的空当成「不限」，一家没配社区的菜摊
             * 会突然铺满全平台；把自送的空当成「谁也看不到」，存量的上门商家
             * 在迁移当天集体从 C 端消失 —— 而且都不报错。
             */
            return deliveryOn ? communityQueryPort.openCommunityNos() : List.of();
        }

        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (MchServiceArea a : areas) {
            if (AREA_COMMUNITY.equals(a.getLevel())) {
                out.add(a.getRefCode());
            } else {
                /*
                 * 街道 / 区县 / 城市都走前缀展开：国标码是层级的，
                 * 330106 命中 330106（挂到区）与 330106002（挂到街道）两种归属。
                 * 这正是当初坚持用国标码而不自造的回报。
                 */
                out.addAll(communityQueryPort.openCommunityNosUnderRegion(a.getRefCode()));
            }
        }
        return List.copyOf(out);
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
        List<ai.neargo.shop.merchant.entity.MchFulfillmentChannel> rows =
                DataScopeContext.executeWithoutScope(() -> fulfillmentChannelMapper.selectList(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers
                                .<ai.neargo.shop.merchant.entity.MchFulfillmentChannel>lambdaQuery()
                                .eq(ai.neargo.shop.merchant.entity.MchFulfillmentChannel::getEntityNo, merchantNo)
                                .eq(storeNo != null && !storeNo.isBlank(),
                                        ai.neargo.shop.merchant.entity.MchFulfillmentChannel::getStoreNo, storeNo)
                                .eq(ai.neargo.shop.merchant.entity.MchFulfillmentChannel::getScopeMode, "SUBSET")));
        if (rows.isEmpty()) {
            return enabled;
        }
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>(enabled);
        for (var row : rows) {
            if (!out.contains(row.getChannel())) {
                continue;
            }
            // 子集展开与可见性同一套规则：COMMUNITY 直选 + 区划前缀展开
            List<String> areaNos = DataScopeContext.executeWithoutScope(() -> channelAreaMapper.selectList(
                            com.baomidou.mybatisplus.core.toolkit.Wrappers
                                    .<ai.neargo.shop.merchant.entity.MchChannelArea>lambdaQuery()
                                    .eq(ai.neargo.shop.merchant.entity.MchChannelArea::getStoreNo, row.getStoreNo())
                                    .eq(ai.neargo.shop.merchant.entity.MchChannelArea::getChannel, row.getChannel())))
                    .stream().map(ai.neargo.shop.merchant.entity.MchChannelArea::getAreaNo).toList();
            boolean hit = false;
            if (!areaNos.isEmpty()) {
                List<MchServiceArea> areas = DataScopeContext.executeWithoutScope(() ->
                        serviceAreaMapper.selectList(Wrappers.<MchServiceArea>lambdaQuery()
                                .in(MchServiceArea::getAreaNo, areaNos)
                                .eq(MchServiceArea::getStatus, AREA_ACTIVE)));
                for (MchServiceArea a : areas) {
                    if (AREA_COMMUNITY.equals(a.getLevel())
                            ? communityNo.equals(a.getRefCode())
                            : communityQueryPort.openCommunityNosUnderRegion(a.getRefCode()).contains(communityNo)) {
                        hit = true;
                        break;
                    }
                }
            }
            if (!hit) {
                out.remove(row.getChannel());
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
                        nvl(store.getStatus()), store.getLatE6(), store.getLngE6()));
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
                m.getBreachCount() == null ? 0 : m.getBreachCount()));
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
                    m.getBreachCount() == null ? 0 : m.getBreachCount()));
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
            return existing.getEntityNo();
        }

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

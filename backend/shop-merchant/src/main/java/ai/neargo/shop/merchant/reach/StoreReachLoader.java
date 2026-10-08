package ai.neargo.shop.merchant.reach;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.merchant.entity.MchChannelArea;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchFulfillmentChannel;
import ai.neargo.shop.merchant.entity.MchServiceArea;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.merchant.reach.ReachRule.Area;
import ai.neargo.shop.merchant.reach.ReachRule.Route;
import ai.neargo.shop.merchant.reach.ReachRule.StoreReach;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把 {@link ReachRule} 要的配置从库里读出来：门店的经营范围（{@code mch_service_area}，V381 起门店级）、
 * 门店每一路开没开与范围（{@code mch_fulfillment_channel} / {@code mch_channel_area}）。
 *
 * <p>只读、不缓存：这几张表加起来几十行，缓存省下的那点查询换来的是
 * 「设置改了、读到旧值」—— 那正是这次改造要消灭的东西。
 *
 * <p>一律绕开数据域：可见性与下单闸是全局判断，不该因为调用方带着某个数据域就看不见配置行。
 */
@Component
public class StoreReachLoader {

    /** 旧单值列 {@code fulfillment_reach}：channel 表一行都没有（或全关）的门店回落到它 */
    private static final String LEGACY_PICKUP = "PICKUP";
    private static final String LEGACY_SHIPPING = "SHIPPING";

    private final MerchantMappers.MchEntityMapper entityMapper;
    private final MerchantMappers.MchStoreMapper storeMapper;
    private final MerchantMappers.FulfillmentChannelMapper channelMapper;
    private final MerchantMappers.ChannelAreaMapper channelAreaMapper;
    private final MerchantMappers.ServiceAreaMapper serviceAreaMapper;

    public StoreReachLoader(MerchantMappers.MchEntityMapper entityMapper,
                            MerchantMappers.MchStoreMapper storeMapper,
                            MerchantMappers.FulfillmentChannelMapper channelMapper,
                            MerchantMappers.ChannelAreaMapper channelAreaMapper,
                            MerchantMappers.ServiceAreaMapper serviceAreaMapper) {
        this.entityMapper = entityMapper;
        this.storeMapper = storeMapper;
        this.channelMapper = channelMapper;
        this.channelAreaMapper = channelAreaMapper;
        this.serviceAreaMapper = serviceAreaMapper;
    }

    /**
     * 一家店的配置：<b>这家店自己的经营范围</b>（V381 起范围是门店级的）+ 这家店开着的路 + 它的子集。
     *
     * <p>{@code storeNo} 为空时取<b>默认店</b>（与 B 端「不带门店号就是默认店」同一口径）。
     * 要「主体并集」口径（商家覆盖哪儿、谁服务这个小区）请用 {@link #loadEach}：
     * 范围门店级之后，把各店的 INCLUDE/EXCLUDE 混成一份再判是<b>错的</b>——
     * A 店排除的楼会把 B 店纳入的同一栋一起减掉。并集必须是「任一门店覆盖」。
     */
    public StoreReach load(MchEntity m, String storeNo) {
        String sNo = storeNo == null || storeNo.isBlank() ? defaultStoreNo(m.getEntityNo()) : storeNo;
        if (sNo == null) {
            // 主体还没有任何门店：没有范围可言（不是「全国」）
            return build(m, null, List.of(), List.of(), List.of());
        }
        List<MchFulfillmentChannel> channels = DataScopeContext.executeWithoutScope(() ->
                channelMapper.selectList(Wrappers.<MchFulfillmentChannel>lambdaQuery()
                        .eq(MchFulfillmentChannel::getEntityNo, m.getEntityNo())
                        .eq(MchFulfillmentChannel::getStoreNo, sNo)));
        return build(m, sNo, areasOfStores(List.of(sNo)), channels, subsetsOf(List.of(sNo)));
    }

    /**
     * 主体名下<b>每一家 ACTIVE 门店</b>各一份配置 —— 「主体口径」= 这些门店<b>足迹</b>的并集，
     * 调用方逐店判、任一命中即算（可达社区取并、送不送得到取或）。停用的店不服务，不在里面。
     *
     * <p><b>路一律按「全部」，不套门店的 SUBSET</b>：与改门店级之前的主体口径逐字一致 ——
     * 「这家商家覆盖哪儿」问的是足迹，不该被某一路的收窄裁小（SUBSET 只在按门店判时生效）。
     */
    public List<StoreReach> loadEach(MchEntity m) {
        List<MchStore> stores = DataScopeContext.executeWithoutScope(() ->
                storeMapper.selectList(Wrappers.<MchStore>lambdaQuery()
                        .eq(MchStore::getEntityNo, m.getEntityNo())
                        .eq(MchStore::getStatus, MchStore.ACTIVE)
                        .orderByAsc(MchStore::getId)));
        if (stores.isEmpty()) {
            return List.of();
        }
        List<String> storeNos = stores.stream().map(MchStore::getStoreNo).toList();
        Map<String, List<MchServiceArea>> areas = group(areasOfStores(storeNos), MchServiceArea::getStoreNo);
        Map<String, List<MchFulfillmentChannel>> channels = group(DataScopeContext.executeWithoutScope(() ->
                channelMapper.selectList(Wrappers.<MchFulfillmentChannel>lambdaQuery()
                        .eq(MchFulfillmentChannel::getEntityNo, m.getEntityNo()))), MchFulfillmentChannel::getStoreNo);
        List<StoreReach> out = new ArrayList<>();
        for (String sNo : storeNos) {
            StoreReach r = build(m, null, areas.getOrDefault(sNo, List.of()),
                    channels.getOrDefault(sNo, List.of()), List.of());
            // build 用 null 门店号是为了让路走「全部」；门店号补回去，调用方要知道是哪家店
            out.add(new StoreReach(r.entityNo(), sNo, r.routes(), r.includes(), r.excludes()));
        }
        return out;
    }

    /**
     * 范围预览：用端上那一份范围行代替<b>这家店</b>库里的（status 一律当 ACTIVE，预览没有「待审」这一档），
     * 路与子集照这家店的配。问的是「这家店改成这样会覆盖哪儿」—— 与保存后的可达同一个口径。
     */
    public StoreReach preview(MchEntity m, String storeNo, List<MchServiceArea> rows) {
        String sNo = storeNo == null || storeNo.isBlank() ? defaultStoreNo(m.getEntityNo()) : storeNo;
        List<MchFulfillmentChannel> channels = sNo == null ? List.of() : DataScopeContext.executeWithoutScope(() ->
                channelMapper.selectList(Wrappers.<MchFulfillmentChannel>lambdaQuery()
                        .eq(MchFulfillmentChannel::getEntityNo, m.getEntityNo())
                        .eq(MchFulfillmentChannel::getStoreNo, sNo)));
        return build(m, sNo, rows, channels, sNo == null ? List.of() : subsetsOf(List.of(sNo)));
    }

    /** 默认店；没有默认标记时取建店最早的那家。主体没有门店时为 null */
    private String defaultStoreNo(String entityNo) {
        MchStore st = DataScopeContext.executeWithoutScope(() ->
                storeMapper.selectOne(Wrappers.<MchStore>lambdaQuery()
                        .eq(MchStore::getEntityNo, entityNo)
                        .orderByDesc(MchStore::getIsDefault)
                        .orderByAsc(MchStore::getId)
                        .last("limit 1")));
        return st == null ? null : st.getStoreNo();
    }

    /** 全平台 ACTIVE 主体下的 ACTIVE 门店，每家店一份。反查「谁服务这个小区」用 */
    public List<StoreReach> allServing() {
        List<MchEntity> entities = DataScopeContext.executeWithoutScope(() ->
                entityMapper.selectList(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getStatus, MchEntity.ACTIVE)));
        if (entities.isEmpty()) {
            return List.of();
        }
        List<String> entityNos = entities.stream().map(MchEntity::getEntityNo).toList();
        List<MchStore> stores = DataScopeContext.executeWithoutScope(() ->
                storeMapper.selectList(Wrappers.<MchStore>lambdaQuery()
                        .in(MchStore::getEntityNo, entityNos)
                        .eq(MchStore::getStatus, MchStore.ACTIVE)
                        .orderByAsc(MchStore::getId)));
        if (stores.isEmpty()) {
            return List.of();
        }
        Map<String, List<MchServiceArea>> areas = group(
                areasOfStores(stores.stream().map(MchStore::getStoreNo).toList()), MchServiceArea::getStoreNo);
        Map<String, List<MchFulfillmentChannel>> channels = group(DataScopeContext.executeWithoutScope(() ->
                channelMapper.selectList(Wrappers.<MchFulfillmentChannel>lambdaQuery()
                        .in(MchFulfillmentChannel::getEntityNo, entityNos))), MchFulfillmentChannel::getStoreNo);
        Map<String, List<MchChannelArea>> subsets = group(
                subsetsOf(stores.stream().map(MchStore::getStoreNo).toList()), MchChannelArea::getStoreNo);
        Map<String, MchEntity> byNo = new HashMap<>();
        entities.forEach(e -> byNo.put(e.getEntityNo(), e));
        List<StoreReach> out = new ArrayList<>();
        for (MchStore st : stores) {
            MchEntity m = byNo.get(st.getEntityNo());
            out.add(build(m, st.getStoreNo(),
                    areas.getOrDefault(st.getStoreNo(), List.of()),
                    channels.getOrDefault(st.getStoreNo(), List.of()),
                    subsets.getOrDefault(st.getStoreNo(), List.of())));
        }
        return out;
    }

    private StoreReach build(MchEntity m, String storeNo, List<MchServiceArea> areas,
                             List<MchFulfillmentChannel> channels, List<MchChannelArea> subsets) {
        List<Area> includes = new ArrayList<>();
        List<Area> excludes = new ArrayList<>();
        for (MchServiceArea a : areas) {
            Area area = new Area(a.getAreaNo(), a.getLevel(), a.getRefCode());
            if (MchServiceArea.MODE_EXCLUDE.equals(a.getMode())) {
                // 缩小自己的范围不需要审核：待审的排除也立即生效
                excludes.add(area);
            } else if (MchServiceArea.ACTIVE.equals(a.getStatus())) {
                includes.add(area);
            }
        }
        return new StoreReach(m.getEntityNo(), storeNo, routes(m, storeNo, channels, subsets),
                List.copyOf(includes), List.copyOf(excludes));
    }

    private static List<Route> routes(MchEntity m, String storeNo, List<MchFulfillmentChannel> channels,
                                      List<MchChannelArea> subsets) {
        Map<String, Route> out = new LinkedHashMap<>();
        for (MchFulfillmentChannel ch : channels) {
            // 运营锁路：锁着的路买家侧不可选 —— 与 enabledFulfillments 同一个口径
            if (!Boolean.TRUE.equals(ch.getEnabled()) || Boolean.TRUE.equals(ch.getOpsLocked())) {
                continue;
            }
            if (storeNo != null && MchFulfillmentChannel.SCOPE_SUBSET.equals(ch.getScopeMode())) {
                Set<String> picked = new LinkedHashSet<>();
                for (MchChannelArea ca : subsets) {
                    if (ch.getChannel().equals(ca.getChannel())) {
                        picked.add(ca.getAreaNo());
                    }
                }
                out.put(ch.getChannel(), new Route(ch.getChannel(), Set.copyOf(picked)));
            } else {
                // 主体口径一律「全部」；同一路多家店都开着时只留一条
                out.putIfAbsent(ch.getChannel(), Route.all(ch.getChannel()));
            }
        }
        if (!out.isEmpty()) {
            return List.copyOf(out.values());
        }
        /*
         * 一路都没开（或该店还没迁到 channel 模型）→ 回落旧单值列。语义与迁移前逐字一致：
         *   SHIPPING → 快递；PICKUP / 空 → 自提（没框 = 谁也看不到）；其余 → 自送（没框 = 不限）。
         */
        String reach = m.getFulfillmentReach() == null ? LEGACY_PICKUP : m.getFulfillmentReach();
        if (LEGACY_SHIPPING.equals(reach)) {
            return List.of(Route.all(Fulfillments.EXPRESS));
        }
        if (LEGACY_PICKUP.equals(reach)) {
            return List.of(Route.all(Fulfillments.STORE_PICKUP));
        }
        return List.of(Route.all(Fulfillments.MERCHANT_DELIVERY));
    }

    /** 这几家门店各自的经营范围（V381 门店级）。store_no 为空的孤行不属于任何门店，不读 */
    private List<MchServiceArea> areasOfStores(Collection<String> storeNos) {
        if (storeNos.isEmpty()) {
            return List.of();
        }
        return DataScopeContext.executeWithoutScope(() ->
                serviceAreaMapper.selectList(Wrappers.<MchServiceArea>lambdaQuery()
                        .in(MchServiceArea::getStoreNo, storeNos)));
    }

    private List<MchChannelArea> subsetsOf(Collection<String> storeNos) {
        return DataScopeContext.executeWithoutScope(() ->
                channelAreaMapper.selectList(Wrappers.<MchChannelArea>lambdaQuery()
                        .in(MchChannelArea::getStoreNo, storeNos)));
    }

    private static <T> Map<String, List<T>> group(List<T> rows, java.util.function.Function<T, String> key) {
        Map<String, List<T>> out = new HashMap<>();
        for (T r : rows) {
            String k = key.apply(r);
            if (k != null) {
                out.computeIfAbsent(k, x -> new ArrayList<>()).add(r);
            }
        }
        return out;
    }
}

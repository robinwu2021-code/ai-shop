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
 * 把 {@link ReachRule} 要的配置从库里读出来：主体范围（{@code mch_service_area}）、
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
     * 一家店的配置。{@code storeNo} 为空 = 主体口径：各店开着的路取并集、一律按「全部」——
     * 「这家商家覆盖哪儿」是主体级的问题，不该被某家店的子集裁小。
     */
    public StoreReach load(MchEntity m, String storeNo) {
        List<MchServiceArea> areas = areasOf(List.of(m.getEntityNo()));
        List<MchFulfillmentChannel> channels = DataScopeContext.executeWithoutScope(() ->
                channelMapper.selectList(Wrappers.<MchFulfillmentChannel>lambdaQuery()
                        .eq(MchFulfillmentChannel::getEntityNo, m.getEntityNo())
                        .eq(storeNo != null && !storeNo.isBlank(), MchFulfillmentChannel::getStoreNo, storeNo)));
        boolean entityLevel = storeNo == null || storeNo.isBlank();
        List<MchChannelArea> subsets = entityLevel ? List.of() : subsetsOf(List.of(storeNo));
        return build(m, entityLevel ? null : storeNo, areas, channels, subsets);
    }

    /**
     * 范围预览：用端上那一份范围行代替库里的（status 一律当 ACTIVE，预览没有「待审」这一档）。
     * 走主体口径 —— 与预览「改成这样，这家商家会覆盖哪儿」问的是同一件事。
     */
    public StoreReach preview(MchEntity m, List<MchServiceArea> rows) {
        List<MchFulfillmentChannel> channels = DataScopeContext.executeWithoutScope(() ->
                channelMapper.selectList(Wrappers.<MchFulfillmentChannel>lambdaQuery()
                        .eq(MchFulfillmentChannel::getEntityNo, m.getEntityNo())));
        return build(m, null, rows, channels, List.of());
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
        Map<String, List<MchServiceArea>> areas = group(areasOf(entityNos), MchServiceArea::getEntityNo);
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
                    areas.getOrDefault(m.getEntityNo(), List.of()),
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

    private List<MchServiceArea> areasOf(Collection<String> entityNos) {
        return DataScopeContext.executeWithoutScope(() ->
                serviceAreaMapper.selectList(Wrappers.<MchServiceArea>lambdaQuery()
                        .in(MchServiceArea::getEntityNo, entityNos)));
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

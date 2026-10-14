package ai.neargo.shop.merchant.reach;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.entity.MchChannelArea;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchFulfillmentChannel;
import ai.neargo.shop.merchant.entity.MchServiceArea;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 装一份全平台门店属性快照（ADR-034）。
 *
 * <p>读五张小表：ACTIVE 主体 × ACTIVE 门店、各店履约路与子集、各店范围项（<b>只取判属性要的三列</b>，
 * 不读 geometry —— 那是精判时按 area_no 单取的）。与被删掉的 {@code allServing()} 的区别在于：
 * 这里<b>不组装 includes/excludes 明细</b>，只回答「有没有不限」「有没有各维度的排除」——
 * 明细由索引点查按消费者画像现查，不再整表搬进内存。
 */
@Component
public class ReachSnapshotLoader {

    private final MerchantMappers.MchEntityMapper entityMapper;
    private final MerchantMappers.MchStoreMapper storeMapper;
    private final MerchantMappers.FulfillmentChannelMapper channelMapper;
    private final MerchantMappers.ChannelAreaMapper channelAreaMapper;
    private final MerchantMappers.ServiceAreaMapper serviceAreaMapper;

    public ReachSnapshotLoader(MerchantMappers.MchEntityMapper entityMapper,
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

    public ReachSnapshot load() {
        List<MchEntity> entities = DataScopeContext.executeWithoutScope(() ->
                entityMapper.selectList(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getStatus, MchEntity.ACTIVE)));
        if (entities.isEmpty()) {
            return new ReachSnapshot(Map.of(), Set.of());
        }
        List<String> entityNos = entities.stream().map(MchEntity::getEntityNo).toList();
        List<MchStore> stores = DataScopeContext.executeWithoutScope(() ->
                storeMapper.selectList(Wrappers.<MchStore>lambdaQuery()
                        .in(MchStore::getEntityNo, entityNos)
                        .eq(MchStore::getStatus, MchStore.ACTIVE)
                        .orderByAsc(MchStore::getId)));
        if (stores.isEmpty()) {
            return new ReachSnapshot(Map.of(), Set.of());
        }
        List<String> storeNos = stores.stream().map(MchStore::getStoreNo).toList();

        Map<String, List<MchFulfillmentChannel>> channels = group(DataScopeContext.executeWithoutScope(() ->
                        channelMapper.selectList(Wrappers.<MchFulfillmentChannel>lambdaQuery()
                                .in(MchFulfillmentChannel::getEntityNo, entityNos))),
                MchFulfillmentChannel::getStoreNo);
        Map<String, List<MchChannelArea>> subsets = group(subsetsOf(storeNos), MchChannelArea::getStoreNo);
        Map<String, List<MchServiceArea>> areas = group(flagsOfStores(storeNos), MchServiceArea::getStoreNo);

        Map<String, MchEntity> byNo = new HashMap<>();
        entities.forEach(e -> byNo.put(e.getEntityNo(), e));

        Map<String, StoreMeta> metas = new LinkedHashMap<>(stores.size());
        Set<String> unlimited = new HashSet<>();
        for (MchStore st : stores) {
            MchEntity m = byNo.get(st.getEntityNo());
            List<MchServiceArea> own = areas.getOrDefault(st.getStoreNo(), List.of());
            boolean isUnlimited = false;
            boolean adminExclude = false;
            boolean communityExclude = false;
            boolean polygonExclude = false;
            for (MchServiceArea a : own) {
                boolean exclude = MchServiceArea.MODE_EXCLUDE.equals(a.getMode());
                if (!exclude) {
                    // 纳入要生效才算；「不限」是唯一一种不靠点查、必须由快照补候选的纳入项
                    if (MchServiceArea.ACTIVE.equals(a.getStatus())
                            && MchServiceArea.LEVEL_UNLIMITED.equals(a.getLevel())) {
                        isUnlimited = true;
                    }
                    continue;
                }
                // 排除不看 status —— 缩小自己的范围不需要审核
                if (MchServiceArea.ADMIN_LEVELS.contains(a.getLevel())) {
                    adminExclude = true;
                } else if (MchServiceArea.LEVEL_COMMUNITY.equals(a.getLevel())) {
                    communityExclude = true;
                } else if (MchServiceArea.LEVEL_POLYGON.equals(a.getLevel())) {
                    polygonExclude = true;
                }
            }
            metas.put(st.getStoreNo(), new StoreMeta(st.getEntityNo(), st.getStoreNo(),
                    StoreRoutes.of(m, st.getStoreNo(),
                            channels.getOrDefault(st.getStoreNo(), List.of()),
                            subsets.getOrDefault(st.getStoreNo(), List.of())),
                    isUnlimited, adminExclude, communityExclude, polygonExclude));
            if (isUnlimited) {
                unlimited.add(st.getStoreNo());
            }
        }
        return new ReachSnapshot(metas, unlimited);
    }

    /** 只取判属性要的几列：level / mode / status。**不读 geometry** —— 精判时按 area_no 单取 */
    private List<MchServiceArea> flagsOfStores(Collection<String> storeNos) {
        if (storeNos.isEmpty()) {
            return List.of();
        }
        return DataScopeContext.executeWithoutScope(() -> serviceAreaMapper.selectList(
                Wrappers.<MchServiceArea>lambdaQuery()
                        .select(MchServiceArea::getStoreNo, MchServiceArea::getLevel,
                                MchServiceArea::getMode, MchServiceArea::getStatus)
                        .in(MchServiceArea::getStoreNo, storeNos)));
    }

    private List<MchChannelArea> subsetsOf(Collection<String> storeNos) {
        if (storeNos.isEmpty()) {
            return List.of();
        }
        return DataScopeContext.executeWithoutScope(() -> channelAreaMapper.selectList(
                Wrappers.<MchChannelArea>lambdaQuery().in(MchChannelArea::getStoreNo, storeNos)));
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

package ai.neargo.shop.merchant.reach;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.entity.MchServiceArea;
import ai.neargo.shop.merchant.mapper.ReachMatchMapper;
import ai.neargo.shop.spi.reach.ConsumerProfile;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用两条索引点查找出命中项（ADR-034）。消费者侧（目录/推荐/详情/下单）走这条。
 *
 * <p>一律 {@code executeWithoutScope}：公共目录与 C 端会话是 SELF 维度，接上数据域就是 {@code 1=0}
 * （见记忆「B 端直查带域表读写皆哑」）。归属由查询条件自身的 store_no 保证。
 */
@Service
public class DbHitFinder implements HitFinder {

    private final ReachMatchMapper mapper;

    public DbHitFinder(ReachMatchMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Map<String, StoreHits> find(ConsumerProfile profile, String storeNo) {
        Map<String, StoreHits.Builder> byStore = new LinkedHashMap<>();
        for (ReachMatchMapper.AreaHitRow r : DataScopeContext.executeWithoutScope(() -> mapper.areaHits(
                profile.ancestors(), profile.communityNo(), profile.parentNo(), storeNo))) {
            byStore.computeIfAbsent(r.storeNo(), k -> new StoreHits.Builder())
                    .area(r.areaNo(), MchServiceArea.MODE_EXCLUDE.equals(r.mode()));
        }
        for (ReachMatchMapper.CellHitRow r : DataScopeContext.executeWithoutScope(() -> mapper.cellHits(
                profile.cellTokens(), storeNo))) {
            byStore.computeIfAbsent(r.storeNo(), k -> new StoreHits.Builder())
                    .cell(r.areaNo(), MchServiceArea.MODE_EXCLUDE.equals(r.mode()),
                            Boolean.TRUE.equals(r.boundary()));
        }
        Map<String, StoreHits> out = new LinkedHashMap<>(byStore.size());
        byStore.forEach((store, b) -> out.put(store, b.build()));
        return out;
    }
}

package ai.neargo.shop.merchant.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.Geo;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import ai.neargo.shop.merchant.service.impl.EntityReachability;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link StoreDirectoryPort} 的实现：C 端「以门店为单位」的门店目录。
 *
 * <p><b>一律绕过数据域</b>：{@code mch_store} 与 {@code mch_entity} 都登记了 MERCHANT 维度，
 * 而这里的调用方是 C 端买家 —— 带着买家会话去查，数据域拼的是 {@code 1=0}，
 * 于是「附近」整页空白、「我的店」一家都没有，而且不报错（见记忆「B端直查带域表读写皆哑」）。
 */
@Component
public class StoreDirectoryPortImpl implements StoreDirectoryPort {

    /** 主体号前缀（{@code BizKey}）。老链接、老店码、旧版小程序带的是它 */
    private static final String ENTITY_PREFIX = "M";
    /** 门店号前缀（{@code BizKey}） */
    private static final String STORE_PREFIX = "ST";

    private final MchStoreMapper storeMapper;
    private final MchEntityMapper entityMapper;
    private final EntityReachability reachability;

    public StoreDirectoryPortImpl(MchStoreMapper storeMapper, MchEntityMapper entityMapper,
                                  EntityReachability reachability) {
        this.storeMapper = storeMapper;
        this.entityMapper = entityMapper;
        this.reachability = reachability;
    }

    @Override
    public Map<String, StoreCard> cards(Collection<String> storeNos) {
        if (storeNos == null || storeNos.isEmpty()) {
            return Map.of();
        }
        List<MchStore> stores = DataScopeContext.executeWithoutScope(() ->
                storeMapper.selectList(Wrappers.<MchStore>lambdaQuery()
                        .in(MchStore::getStoreNo, storeNos)
                        .in(MchStore::getStatus, STORE_ACTIVE, STORE_READONLY)));
        Map<String, MchEntity> entities = activeEntities(
                stores.stream().map(MchStore::getEntityNo).distinct().toList());
        Map<String, StoreCard> out = new LinkedHashMap<>();
        for (MchStore s : stores) {
            MchEntity e = entities.get(s.getEntityNo());
            if (e != null) {
                out.put(s.getStoreNo(), card(s, e));
            }
        }
        return out;
    }

    @Override
    public List<StoreCard> reachableActive(String communityNo, String keyword) {
        var w = Wrappers.<MchEntity>lambdaQuery().eq(MchEntity::getStatus, MchEntity.ACTIVE);
        reachability.apply(w, communityNo);
        Map<String, MchEntity> entities = DataScopeContext.executeWithoutScope(() ->
                        entityMapper.selectList(w)).stream()
                .collect(Collectors.toMap(MchEntity::getEntityNo, Function.identity(), (a, b) -> a));
        if (entities.isEmpty()) {
            return List.of();
        }
        var q = Wrappers.<MchStore>lambdaQuery()
                .in(MchStore::getEntityNo, entities.keySet())
                .eq(MchStore::getStatus, STORE_ACTIVE);
        if (keyword != null && !keyword.isBlank()) {
            q.like(MchStore::getName, keyword.strip());
        }
        return DataScopeContext.executeWithoutScope(() -> storeMapper.selectList(q)).stream()
                .map(s -> card(s, entities.get(s.getEntityNo())))
                .toList();
    }

    @Override
    public Optional<StoreCard> resolve(String no) {
        if (no == null || no.isBlank()) {
            return Optional.empty();
        }
        // 先判 ST 再判 M：两个前缀互不包含，但顺序写死，免得将来加一个 M 开头的新前缀时悄悄截走
        if (no.startsWith(STORE_PREFIX)) {
            return Optional.ofNullable(cards(List.of(no)).get(no));
        }
        if (no.startsWith(ENTITY_PREFIX)) {
            return frontStoreOf(no);
        }
        return Optional.empty();
    }

    @Override
    public Optional<StoreCard> nearestSibling(String storeNo) {
        Optional<StoreCard> self = Optional.ofNullable(cards(List.of(storeNo)).get(storeNo));
        if (self.isEmpty()) {
            return Optional.empty();
        }
        StoreCard me = self.get();
        List<StoreCard> siblings = storesOf(me.entityNo()).stream()
                .filter(StoreCard::active)
                .filter(c -> !c.storeNo().equals(storeNo))
                .toList();
        if (siblings.isEmpty()) {
            return Optional.empty();
        }
        if (me.latE6() != null && me.lngE6() != null) {
            Optional<StoreCard> byDistance = siblings.stream()
                    .filter(c -> c.latE6() != null && c.lngE6() != null)
                    .min(Comparator.comparingInt(c ->
                            Geo.meters(me.latE6(), me.lngE6(), c.latE6(), c.lngE6())));
            if (byDistance.isPresent()) {
                return byDistance;
            }
        }
        return siblings.stream().filter(StoreCard::isDefault).findFirst()
                .or(() -> siblings.stream().findFirst());
    }

    @Override
    public Optional<ai.neargo.shop.spi.user.MerchantQueryPort.StoreFront> front(String storeNo) {
        if (storeNo == null || storeNo.isBlank()) {
            return Optional.empty();
        }
        MchStore s = DataScopeContext.executeWithoutScope(() ->
                storeMapper.selectOne(Wrappers.<MchStore>lambdaQuery()
                        .eq(MchStore::getStoreNo, storeNo).last("limit 1")));
        if (s == null) {
            return Optional.empty();
        }
        String ann = s.effectiveAnnouncement();
        return Optional.of(new ai.neargo.shop.spi.user.MerchantQueryPort.StoreFront(ann,
                // 过期的公告连时间也不给：与 MerchantPortImpl.storeFront 同一处理
                ann.isEmpty() ? null : s.getAnnouncementAt(),
                Objects.requireNonNullElse(s.getOpenHours(), ""),
                Objects.requireNonNullElse(s.getAddress(), ""),
                Objects.requireNonNullElse(s.getStatus(), ""), s.getLatE6(), s.getLngE6()));
    }

    @Override
    public Map<String, String> statuses(Collection<String> storeNos) {
        if (storeNos == null || storeNos.isEmpty()) {
            return Map.of();
        }
        return DataScopeContext.executeWithoutScope(() ->
                        storeMapper.selectList(Wrappers.<MchStore>lambdaQuery()
                                .select(MchStore::getStoreNo, MchStore::getStatus)
                                .in(MchStore::getStoreNo, storeNos)))
                .stream()
                .collect(Collectors.toMap(MchStore::getStoreNo,
                        s -> Objects.requireNonNullElse(s.getStatus(), ""), (a, b) -> a));
    }

    /** 老链接的落点：默认门店（要 ACTIVE）→ 任一 ACTIVE → 任一门店（门户显示暂停营业） */
    private Optional<StoreCard> frontStoreOf(String entityNo) {
        List<StoreCard> all = storesOf(entityNo);
        return all.stream().filter(c -> c.isDefault() && c.active()).findFirst()
                .or(() -> all.stream().filter(StoreCard::active).findFirst())
                .or(() -> all.stream().findFirst());
    }

    /** 一个主体下的全部门店卡片（主体不是 ACTIVE 时为空）。按「默认在前、再按建店顺序」稳定排序 */
    private List<StoreCard> storesOf(String entityNo) {
        MchEntity e = activeEntities(List.of(entityNo)).get(entityNo);
        if (e == null) {
            return List.of();
        }
        return DataScopeContext.executeWithoutScope(() ->
                        storeMapper.selectList(Wrappers.<MchStore>lambdaQuery()
                                .eq(MchStore::getEntityNo, entityNo)
                                .in(MchStore::getStatus, STORE_ACTIVE, STORE_READONLY)
                                .orderByDesc(MchStore::getIsDefault)
                                .orderByAsc(MchStore::getId)))
                .stream().map(s -> card(s, e)).toList();
    }

    private Map<String, MchEntity> activeEntities(Collection<String> entityNos) {
        if (entityNos.isEmpty()) {
            return Map.of();
        }
        return DataScopeContext.executeWithoutScope(() ->
                        entityMapper.selectList(Wrappers.<MchEntity>lambdaQuery()
                                .in(MchEntity::getEntityNo, entityNos)
                                .eq(MchEntity::getStatus, MchEntity.ACTIVE)))
                .stream()
                .collect(Collectors.toMap(MchEntity::getEntityNo, Function.identity(), (a, b) -> a));
    }

    private static StoreCard card(MchStore s, MchEntity e) {
        return new StoreCard(s.getStoreNo(), s.getName(), s.getEntityNo(),
                Objects.requireNonNullElse(e.getLogo(), ""),
                s.getStatus(), Boolean.TRUE.equals(s.getIsDefault()),
                Objects.requireNonNullElse(s.getOpenHours(), ""),
                Objects.requireNonNullElse(s.getAddress(), ""),
                s.getLatE6(), s.getLngE6(),
                s.getRating() == null ? 0d : s.getRating() / 10d,
                s.getRatingCount() == null ? 0 : s.getRatingCount());
    }
}

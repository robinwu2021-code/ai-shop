package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchServiceArea;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.merchant.reach.ReachSnapshot;
import ai.neargo.shop.merchant.reach.ReachSnapshotCache;
import ai.neargo.shop.merchant.reach.StoreMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 门店属性快照真的在缓存、也真的会失效（ADR-034 AC9）。
 *
 * <p>判据用 {@code loadCount()} 这个对照量：没有它，「缓存命中」与「每次都重装但结果一样」
 * 在断言上完全一样 —— 那条测试会变成恒绿。
 */
@SpringBootTest
@ActiveProfiles("test")
class ReachSnapshotCacheTest {

    @Autowired
    private ReachSnapshotCache cache;
    @Autowired
    private MerchantMappers.MchEntityMapper entityMapper;
    @Autowired
    private MerchantMappers.MchStoreMapper storeMapper;
    @Autowired
    private MerchantMappers.ServiceAreaMapper areaMapper;

    @Test
    @DisplayName("★★★ 两次 get 只装一次；evict 之后下一次 get 重装")
    void cachesAndEvicts() {
        cache.evict();
        long base = cache.loadCount();

        ReachSnapshot first = cache.get();
        assertThat(cache.loadCount()).as("第一次要真装").isEqualTo(base + 1);

        ReachSnapshot second = cache.get();
        assertThat(cache.loadCount()).as("TTL 内第二次不该再装").isEqualTo(base + 1);
        assertThat(second).as("同一份对象").isSameAs(first);

        cache.evict();
        ReachSnapshot third = cache.get();
        assertThat(cache.loadCount()).as("evict 后要重装").isEqualTo(base + 2);
        assertThat(third).isNotSameAs(first);
    }

    @Test
    @DisplayName("★★★ 显式 UNLIMITED 的店进 unlimitedStores；没有任何范围项的店不进")
    void unlimitedStoresReflectExplicitItem() {
        String s = Long.toString(System.nanoTime(), 36);
        String entity = "E-SNAP-" + s;
        String withUnlimited = "ST-SNAP-U-" + s;
        String without = "ST-SNAP-N-" + s;

        entity(entity);
        store(entity, withUnlimited);
        store(entity, without);
        area(entity, withUnlimited, MchServiceArea.LEVEL_UNLIMITED, MchServiceArea.UNLIMITED_REF,
                MchServiceArea.MODE_INCLUDE, MchServiceArea.ACTIVE);

        cache.evict();
        ReachSnapshot snap = cache.get();

        assertThat(snap.unlimitedStores()).contains(withUnlimited).doesNotContain(without);
        StoreMeta u = snap.meta(withUnlimited);
        StoreMeta n = snap.meta(without);
        assertThat(u).isNotNull();
        assertThat(u.unlimited()).isTrue();
        assertThat(n).isNotNull();
        assertThat(n.unlimited()).as("没框任何范围的店不是「不限」").isFalse();
        assertThat(n.routes()).as("一路没开要回落旧单值列，不该是空").isNotEmpty();
    }

    @Test
    @DisplayName("★★ 各维度排除分别被记下；排除不看 status（待审的排除也算）")
    void excludeFlagsPerDimension() {
        String s = Long.toString(System.nanoTime(), 36);
        String entity = "E-SNAPX-" + s;
        String adminEx = "ST-SNAPX-A-" + s;
        String cmtEx = "ST-SNAPX-C-" + s;
        String polyEx = "ST-SNAPX-P-" + s;

        entity(entity);
        store(entity, adminEx);
        store(entity, cmtEx);
        store(entity, polyEx);
        // 待审的排除：status 故意给 PENDING，仍要算
        area(entity, adminEx, MchServiceArea.LEVEL_PROVINCE, "65", MchServiceArea.MODE_EXCLUDE, MchServiceArea.PENDING);
        area(entity, cmtEx, MchServiceArea.LEVEL_COMMUNITY, "CM-X-" + s, MchServiceArea.MODE_EXCLUDE, MchServiceArea.ACTIVE);
        area(entity, polyEx, MchServiceArea.LEVEL_POLYGON, "fp" + s, MchServiceArea.MODE_EXCLUDE, MchServiceArea.ACTIVE);

        cache.evict();
        ReachSnapshot snap = cache.get();

        assertThat(snap.meta(adminEx).hasAdminExclude()).isTrue();
        assertThat(snap.meta(adminEx).hasCommunityExclude()).isFalse();
        assertThat(snap.meta(adminEx).hasPolygonExclude()).isFalse();
        assertThat(snap.meta(cmtEx).hasCommunityExclude()).isTrue();
        assertThat(snap.meta(cmtEx).hasAdminExclude()).isFalse();
        assertThat(snap.meta(polyEx).hasPolygonExclude()).isTrue();
        assertThat(snap.meta(polyEx).hasAdminExclude()).isFalse();
    }

    // ── 种子 ──────────────────────────────────────────────────────────────

    private void entity(String entityNo) {
        MchEntity e = new MchEntity();
        e.setEntityNo(entityNo);
        e.setName("快照用主体 " + entityNo);
        e.setStatus(MchEntity.ACTIVE);
        DataScopeContext.executeWithoutScope(() -> entityMapper.insert(e));
    }

    private void store(String entityNo, String storeNo) {
        MchStore st = new MchStore();
        st.setEntityNo(entityNo);
        st.setStoreNo(storeNo);
        st.setName("快照用门店 " + storeNo);
        st.setStatus(MchStore.ACTIVE);
        st.setIsDefault(false);
        DataScopeContext.executeWithoutScope(() -> storeMapper.insert(st));
    }

    private void area(String entityNo, String storeNo, String level, String ref, String mode, String status) {
        MchServiceArea row = new MchServiceArea();
        row.setAreaNo(BizKey.next(BizKey.SERVICE_AREA));
        row.setEntityNo(entityNo);
        row.setStoreNo(storeNo);
        row.setLevel(level);
        row.setRefCode(ref);
        row.setMode(mode);
        row.setStatus(status);
        row.setSource("SELF");
        DataScopeContext.executeWithoutScope(() -> areaMapper.insert(row));
    }
}

package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchFulfillmentChannel;
import ai.neargo.shop.merchant.entity.MchServiceArea;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import db.migration.V397__backfill_unlimited_service_area;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V397 存量回填的判据（ADR-034 AC6）—— 整次改造最危险的一格。
 *
 * <p>旧判定有一条隐式分支「一条 ACTIVE 纳入项都没有 + 开着快递或自送 = 覆盖全部开放小区」，
 * 它被删掉了。删掉而不回填，这批门店会在上线当天<b>集体从 C 端消失，且不报错</b>。
 * 这条测试逐形态验「谁该补、谁不该补」，并验幂等。
 *
 * <p>判据在三处必须一致：{@code StoreRoutes.of}（运行时）、V397（迁移）、
 * {@code scripts/reach/unlimited-backfill-preview.sql}（上线前对照量）。
 */
@SpringBootTest
@ActiveProfiles("test")
class UnlimitedBackfillTest {

    @Autowired
    private DataSource dataSource;
    @Autowired
    private MerchantMappers.MchEntityMapper entityMapper;
    @Autowired
    private MerchantMappers.MchStoreMapper storeMapper;
    @Autowired
    private MerchantMappers.ServiceAreaMapper areaMapper;
    @Autowired
    private MerchantMappers.FulfillmentChannelMapper channelMapper;

    @Test
    @DisplayName("★★★ 只补「没框范围 + 有不限落点的路」那几家；自提、子集路、已框范围的都不补")
    void backfillsExactlyTheImplicitlyUnlimitedOnes() throws Exception {
        String s = Long.toString(System.nanoTime(), 36);

        // ① 没框范围 + 快递 ALL → 该补
        String expressAll = seed(s + "a", "PICKUP", ch("EXPRESS", "ALL", true, false), null);
        // ② 没框范围 + 自送 ALL → 该补
        String deliveryAll = seed(s + "b", "PICKUP", ch("MERCHANT_DELIVERY", "ALL", true, false), null);
        // ③ 没框范围 + 快递但是 SUBSET → 不补（子集路不吃「不限」）
        String expressSubset = seed(s + "c", "PICKUP", ch("EXPRESS", "SUBSET", true, false), null);
        // ④ 没框范围 + 只开自提 → 不补（自提没有落点）
        String pickupOnly = seed(s + "d", "PICKUP", ch("STORE_PICKUP", "ALL", true, false), null);
        // ⑤ 没框范围 + 快递被运营锁 → 不补（锁着的路买家侧不可选）
        String locked = seed(s + "e", "PICKUP", ch("EXPRESS", "ALL", true, true), null);
        // ⑥ 没框范围 + 快递但 enabled=0 → 落到旧单值列 SHIPPING → 该补
        String disabledButShipping = seed(s + "f", "SHIPPING", ch("EXPRESS", "ALL", false, false), null);
        // ⑦ 一条路都没有 + 主体旧列 SHIPPING → 该补
        String legacyShipping = seed(s + "g", "SHIPPING", null, null);
        // ⑧ 一条路都没有 + 主体旧列 PICKUP → 不补
        String legacyPickup = seed(s + "h", "PICKUP", null, null);
        // ⑨ 一条路都没有 + 主体旧列为 NULL → 不补（按自提兜底）
        String legacyNull = seed(s + "i", null, null, null);
        // ⑩ 一条路都没有 + 主体旧列是别的值（ONSITE=自送）→ 该补
        String legacyOnsite = seed(s + "j", "ONSITE", null, null);
        // ⑪ 已经框了范围 + 快递 ALL → 不补（它本来就按框选卖）
        String framed = seed(s + "k", "PICKUP", ch("EXPRESS", "ALL", true, false),
                area -> area.setRefCode("440304"));

        migrate();

        assertThat(backfilled(expressAll)).as("快递 ALL").isTrue();
        assertThat(backfilled(deliveryAll)).as("自送 ALL").isTrue();
        assertThat(backfilled(disabledButShipping)).as("路关着 → 回落旧列 SHIPPING").isTrue();
        assertThat(backfilled(legacyShipping)).as("无路 + 旧列 SHIPPING").isTrue();
        assertThat(backfilled(legacyOnsite)).as("无路 + 旧列 ONSITE（自送）").isTrue();

        assertThat(backfilled(expressSubset)).as("子集路不吃「不限」").isFalse();
        assertThat(backfilled(pickupOnly)).as("自提没有落点").isFalse();
        assertThat(backfilled(locked)).as("被运营锁的路买家侧不可选").isFalse();
        assertThat(backfilled(legacyPickup)).as("无路 + 旧列 PICKUP").isFalse();
        assertThat(backfilled(legacyNull)).as("无路 + 旧列为空，按自提兜底").isFalse();
        assertThat(backfilled(framed)).as("已框范围的按框选卖，不该被补成全平台").isFalse();
    }

    @Test
    @DisplayName("★★★ 幂等：跑两次不会补出第二条")
    void isIdempotent() {
        String s = Long.toString(System.nanoTime(), 36);
        String store = seed(s + "z", "SHIPPING", null, null);

        migrate();
        assertThat(unlimitedRows(store)).hasSize(1);

        migrate();
        assertThat(unlimitedRows(store)).as("重跑不该补出第二条 —— 唯一键也会拦，但判据本身就该幂等").hasSize(1);
    }

    @Test
    @DisplayName("★★ 补出来的行带 created_by 标记，可按它清点与整批撤回")
    void backfilledRowsAreMarked() {
        String s = Long.toString(System.nanoTime(), 36);
        String store = seed(s + "y", "SHIPPING", null, null);
        migrate();

        MchServiceArea row = unlimitedRows(store).get(0);
        assertThat(row.getCreatedBy()).isEqualTo("V397_UNLIMITED_BACKFILL");
        assertThat(row.getLevel()).isEqualTo(MchServiceArea.LEVEL_UNLIMITED);
        assertThat(row.getRefCode()).isEqualTo(MchServiceArea.UNLIMITED_REF);
        assertThat(row.getMode()).isEqualTo(MchServiceArea.MODE_INCLUDE);
        assertThat(row.getStatus()).isEqualTo(MchServiceArea.ACTIVE);
        assertThat(row.getGeometry()).isNull();
        assertThat(row.getAreaNo())
                .as("定长、按门店号确定性生成 —— 重跑得同一个号才能真幂等，拼 store_no 则长度不可控会撞唯一键")
                .startsWith("SVAMIG").hasSize(22);
    }

    // ── 工具 ──────────────────────────────────────────────────────────────

    /** 直接调迁移的 migrate()，只给它一个连接 —— Flyway 的 Context 其余成员这条迁移用不到 */
    private void migrate() {
        try (Connection conn = dataSource.getConnection()) {
            new V397__backfill_unlimited_service_area().migrate(new Context() {
                @Override
                public org.flywaydb.core.api.configuration.Configuration getConfiguration() {
                    return null;
                }

                @Override
                public Connection getConnection() {
                    return conn;
                }
            });
        } catch (Exception e) {
            throw new IllegalStateException("迁移执行失败", e);
        }
    }

    private record Channel(String channel, String scopeMode, boolean enabled, boolean opsLocked) {
    }

    private static Channel ch(String channel, String scopeMode, boolean enabled, boolean opsLocked) {
        return new Channel(channel, scopeMode, enabled, opsLocked);
    }

    /** 造「一个主体 + 一家 ACTIVE 门店」，按需给它一条履约路、一条范围项。返回门店号 */
    private String seed(String tag, String legacyReach, Channel channel,
                        java.util.function.Consumer<MchServiceArea> areaTweak) {
        String entityNo = "E-BF-" + tag;
        String storeNo = "ST-BF-" + tag;

        MchEntity e = new MchEntity();
        e.setEntityNo(entityNo);
        e.setName("回填测试 " + tag);
        e.setStatus(MchEntity.ACTIVE);
        e.setFulfillmentReach(legacyReach);
        DataScopeContext.executeWithoutScope(() -> entityMapper.insert(e));

        MchStore st = new MchStore();
        st.setEntityNo(entityNo);
        st.setStoreNo(storeNo);
        st.setName("回填测试店 " + tag);
        st.setStatus(MchStore.ACTIVE);
        st.setIsDefault(true);
        DataScopeContext.executeWithoutScope(() -> storeMapper.insert(st));

        if (channel != null) {
            MchFulfillmentChannel c = new MchFulfillmentChannel();
            c.setEntityNo(entityNo);
            c.setStoreNo(storeNo);
            c.setChannel(channel.channel());
            c.setScopeMode(channel.scopeMode());
            c.setEnabled(channel.enabled());
            c.setOpsLocked(channel.opsLocked());
            DataScopeContext.executeWithoutScope(() -> channelMapper.insert(c));
        }
        if (areaTweak != null) {
            MchServiceArea a = new MchServiceArea();
            a.setAreaNo(BizKey.next(BizKey.SERVICE_AREA));
            a.setEntityNo(entityNo);
            a.setStoreNo(storeNo);
            a.setLevel(MchServiceArea.LEVEL_DISTRICT);
            a.setMode(MchServiceArea.MODE_INCLUDE);
            a.setStatus(MchServiceArea.ACTIVE);
            a.setSource("SELF");
            areaTweak.accept(a);
            DataScopeContext.executeWithoutScope(() -> areaMapper.insert(a));
        }
        return storeNo;
    }

    private boolean backfilled(String storeNo) {
        return !unlimitedRows(storeNo).isEmpty();
    }

    private List<MchServiceArea> unlimitedRows(String storeNo) {
        return DataScopeContext.executeWithoutScope(() -> areaMapper.selectList(
                Wrappers.<MchServiceArea>lambdaQuery()
                        .eq(MchServiceArea::getStoreNo, storeNo)
                        .eq(MchServiceArea::getLevel, MchServiceArea.LEVEL_UNLIMITED)));
    }
}

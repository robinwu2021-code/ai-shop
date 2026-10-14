package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 给「此前靠<b>隐式</b>规则全平台可见」的存量门店补一条显式 {@code UNLIMITED} 范围项（ADR-034）。
 *
 * <h2>这是整次改造最危险的一格</h2>
 * 旧判定里有一条隐式分支：<b>一条 ACTIVE 纳入项都没有 + 开着快递或自送 = 覆盖全部开放小区</b>。
 * 它被删掉了（因为「没有纳入项」有四种成因 —— 框写到别家店、没物化成行、框成了 EXCLUDE、门店级错位 ——
 * 任何一种都会让商家在不知情的情况下铺满全平台，而且不报错；虹选粮油就是这么「框了嘉逸花园却全平台可见」的）。
 *
 * <p>删掉它而不回填，这批门店会在上线当天<b>集体从 C 端消失</b>，且同样不报错。
 * 所以这条迁移把旧语义<b>逐字翻译</b>成显式的 {@code UNLIMITED} 行：行为零变化，但从此看得见、改得掉。
 *
 * <h2>判据必须与 StoreRoutes 逐字一致</h2>
 * 「开着快递或自送」在代码里不是查一个字段就完事的，它是 {@code StoreRoutes.of} 那段：
 * <ol>
 *   <li>先看 {@code mch_fulfillment_channel}：{@code enabled=1} 且<b>没被运营锁</b>（{@code ops_locked} 非真）的路；
 *       其中 {@code scope_mode='SUBSET'} 的<b>不算</b> —— 子集路不吃「不限」。</li>
 *   <li>一条可用的路都没有（表里没行、或全关、或全被锁）→ 回落主体的旧单值列 {@code fulfillment_reach}：
 *       {@code SHIPPING}→快递（算）、{@code PICKUP} 或 NULL→自提（<b>不算</b>，自提没有落点）、其余→自送（算）。</li>
 * </ol>
 * 这两段与 {@code StoreRoutes} 对不上，就会出现「迁移前实际可见 ≠ 迁移后仍可见」——
 * 而那种偏差没有任何报错，只能靠下面那个对照量发现。
 *
 * <h2>对照量</h2>
 * 上线前对生产库跑 {@code scripts/reach/unlimited-backfill-preview.sql} 得到一个数 N；
 * 迁移后 {@code SELECT COUNT(*) FROM mch_service_area WHERE created_by = 'V397_UNLIMITED_BACKFILL'} 必须等于 N。
 * 不等就说明判据翻译错了，而 {@code created_by} 这个标记让它可以被整批撤掉重来。
 *
 * <p>幂等：按「这家店有没有 UNLIMITED 行」判，不按「表空不空」—— 表里本来就有大量其它粒度的范围行
 * （V181 的注释记过这个坑：复制粘贴来的守卫让迁移「成功」了，只是一行没写）。
 */
public class V397__backfill_unlimited_service_area extends BaseJavaMigration {

    private static final String MARK = "V397_UNLIMITED_BACKFILL";
    private static final String EXPRESS = "EXPRESS";
    private static final String MERCHANT_DELIVERY = "MERCHANT_DELIVERY";
    private static final String LEGACY_PICKUP = "PICKUP";
    private static final String LEGACY_SHIPPING = "SHIPPING";

    /** 一家待回填的门店 */
    private record Target(String entityNo, String storeNo) {
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection conn = context.getConnection();
        List<Target> targets = new ArrayList<>();

        String scan = """
                SELECT s.entity_no, s.store_no, e.fulfillment_reach
                FROM mch_store s
                JOIN mch_entity e ON e.entity_no = s.entity_no
                WHERE s.status = 'ACTIVE' AND s.deleted = 0
                  AND e.status = 'ACTIVE' AND e.deleted = 0
                  AND s.store_no IS NOT NULL
                """;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(scan)) {
            while (rs.next()) {
                String entityNo = rs.getString(1);
                String storeNo = rs.getString(2);
                String legacyReach = rs.getString(3);
                if (hasActiveInclude(conn, storeNo) || hasUnlimited(conn, storeNo)) {
                    continue;
                }
                if (openEndedRoute(conn, storeNo, legacyReach)) {
                    targets.add(new Target(entityNo, storeNo));
                }
            }
        }

        String insert = """
                INSERT INTO mch_service_area
                    (area_no, entity_no, store_no, level, ref_code, mode, status, source,
                     geometry, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
                VALUES (?, ?, ?, 'UNLIMITED', '*', 'INCLUDE', 'ACTIVE', 'SELF',
                        NULL, 'MAIN', NOW(), ?, NOW(), ?, 0, 0)
                """;
        try (PreparedStatement ps = conn.prepareStatement(insert)) {
            for (Target t : targets) {
                ps.setString(1, areaNoOf(t.storeNo()));
                ps.setString(2, t.entityNo());
                ps.setString(3, t.storeNo());
                ps.setString(4, MARK);
                ps.setString(5, MARK);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        System.out.printf("[V397] 显式 UNLIMITED 回填：%d 家门店（标记 created_by=%s）%n", targets.size(), MARK);
    }

    /**
     * 这一行的 {@code area_no}：{@code SVAMIG} + 门店号的 SHA-256 前 16 位十六进制（定长 22）。
     *
     * <p>三个要求同时满足：
     * <ul>
     *   <li><b>确定性</b> —— 同一门店每次生成同一个号，于是迁移重跑（Flyway repair 之后、
     *       或幂等守卫被绕过）只会撞唯一键，而不是插出第二条。自增序号做不到这一点：
     *       它在每次调用里都从 1 开始，同一个库跑第二遍就撞号并整条失败。</li>
     *   <li><b>定长</b> —— 不拼 {@code store_no}：那一列是 VARCHAR(64)，拼出来可能超长被截断，
     *       而截断之后一样撞 {@code uk_service_area_no}。</li>
     *   <li><b>不依赖应用层</b> —— 不用 BizKey：迁移对业务代码的依赖会让它随那些代码一起漂。</li>
     * </ul>
     * 64 bit 的碰撞概率在门店量级上可忽略；真撞了唯一键会拦住，迁移失败总比静默写错好。
     */
    private static String areaNoOf(String storeNo) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(storeNo.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("SVAMIG");
            for (int i = 0; i < 8; i++) {
                sb.append(Character.forDigit((d[i] >> 4) & 0xF, 16)).append(Character.forDigit(d[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 这家店有没有生效的纳入项。旧判定里「一条都没有」才走隐式不限那一支 */
    private boolean hasActiveInclude(Connection conn, String storeNo) throws Exception {
        String sql = "SELECT 1 FROM mch_service_area WHERE store_no = ? AND mode = 'INCLUDE' "
                + "AND status = 'ACTIVE' AND deleted = 0 LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, storeNo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** 幂等守卫：已经有 UNLIMITED 行就不再补（重跑、或人工先加过） */
    private boolean hasUnlimited(Connection conn, String storeNo) throws Exception {
        String sql = "SELECT 1 FROM mch_service_area WHERE store_no = ? AND level = 'UNLIMITED' "
                + "AND deleted = 0 LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, storeNo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * 这家店有没有「不限落点」的路 —— <b>逐字复刻 StoreRoutes.of</b>（见类注释）。
     */
    private boolean openEndedRoute(Connection conn, String storeNo, String legacyReach) throws Exception {
        String sql = "SELECT channel, scope_mode FROM mch_fulfillment_channel "
                + "WHERE store_no = ? AND enabled = 1 AND (ops_locked IS NULL OR ops_locked = 0) AND deleted = 0";
        boolean anyRoute = false;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, storeNo);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    anyRoute = true;
                    String channel = rs.getString(1);
                    if ("SUBSET".equals(rs.getString(2))) {
                        continue;   // 子集路不吃「不限」
                    }
                    if (EXPRESS.equals(channel) || MERCHANT_DELIVERY.equals(channel)) {
                        return true;
                    }
                }
            }
        }
        if (anyRoute) {
            return false;   // 有路但没有一条是「全部」的快递/自送
        }
        // 一条可用的路都没有 → 回落主体旧单值列
        String reach = legacyReach == null ? LEGACY_PICKUP : legacyReach;
        if (LEGACY_SHIPPING.equals(reach)) {
            return true;    // 快递
        }
        if (LEGACY_PICKUP.equals(reach)) {
            return false;   // 自提：没框 = 谁也看不到
        }
        return true;        // 其余 → 自送
    }
}

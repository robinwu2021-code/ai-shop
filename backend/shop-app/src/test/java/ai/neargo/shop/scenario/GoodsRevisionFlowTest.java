package ai.neargo.shop.scenario;

import ai.neargo.shop.product.dto.GoodsRevisionVO;
import ai.neargo.shop.product.entity.PrdGoodsRevision;
import ai.neargo.shop.product.service.GoodsRevisionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 商品提交历史的状态流转（TDD-商品编辑页-录入落点与发布历史 AC11）。
 *
 * <p><b>走真库不打替身</b>：这一组真正会坏的地方在 SQL 与唯一键上 ——
 * {@code nextRevisionNo} 的 MAX+1、{@code uk_goods_revision(goods_no, revision_no)}、
 * 带域表要绕域。替身把这三样全盖住，剩下的只是「我写的 if 跑没跑」。
 */
@SpringBootTest
@ActiveProfiles("test")
class GoodsRevisionFlowTest {

    private static final String GOODS = "GREVTEST01";
    private static final String MINE = "MCHREV0001";
    private static final String OTHERS = "MCHREV0002";

    @Autowired
    private GoodsRevisionService revisions;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        // 还原:这是共享种子库,留下行会让别的类单独跑绿、全量红
        jdbc.update("DELETE FROM prd_goods_revision WHERE goods_no = ?", GOODS);
        jdbc.update("DELETE FROM prd_goods WHERE goods_no = ?", GOODS);
        /*
         * created_at / updated_at 要显式给:生成的 H2 schema 里它们 NOT NULL
         * 而**没有默认值**(生产 MariaDB/MySQL 有 CURRENT_TIMESTAMP)。
         * 这是 H2 与生产库的方言差之一,不给就是 23502。
         */
        jdbc.update("INSERT INTO prd_goods (goods_no, entity_no, title, type, audit_status, on_sale,"
                + " created_at, updated_at) VALUES (?, ?, '历史测试商品', 'PHYSICAL', 'APPROVED', 1,"
                + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", GOODS, MINE);
    }

    @Test
    @DisplayName("★★★ AC11 保存→发布→再保存:版本号递增,上一版翻 SUPERSEDED")
    void saveThenPublishThenSaveAgain() {
        revisions.recordSave(GOODS, MINE, "{\"v\":1}", "商品名称", PrdGoodsRevision.SRC_QUICK_TEXT);
        revisions.recordPublished(GOODS, "staff-A");

        revisions.recordSave(GOODS, MINE, "{\"v\":2}", "售价、限购地区", PrdGoodsRevision.SRC_MANUAL);

        List<GoodsRevisionVO> all = revisions.list(MINE, GOODS);
        assertThat(all).hasSize(2);
        // 新的在前
        assertThat(all.get(0).revisionNo()).isEqualTo(2);
        assertThat(all.get(0).status()).isEqualTo(PrdGoodsRevision.DRAFT);
        assertThat(all.get(0).changeSummary()).isEqualTo("售价、限购地区");
        assertThat(all.get(0).entrySource()).isEqualTo(PrdGoodsRevision.SRC_MANUAL);
        // 第一版已上线
        assertThat(all.get(1).revisionNo()).isEqualTo(1);
        assertThat(all.get(1).status()).isEqualTo(PrdGoodsRevision.ONLINE);
        assertThat(all.get(1).publishedBy()).isEqualTo("staff-A");
        assertThat(all.get(1).publishedAt()).isNotNull();
        assertThat(all.get(1).entrySource()).isEqualTo(PrdGoodsRevision.SRC_QUICK_TEXT);

        // 再发一次:v2 上线、v1 被替换 —— 「线上在售」只能有一行
        revisions.recordPublished(GOODS, "staff-B");
        List<GoodsRevisionVO> after = revisions.list(MINE, GOODS);
        assertThat(after.get(0).status()).isEqualTo(PrdGoodsRevision.ONLINE);
        assertThat(after.get(0).publishedBy()).isEqualTo("staff-B");
        assertThat(after.get(1).status()).isEqualTo(PrdGoodsRevision.SUPERSEDED);
        assertThat(onlineRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("★★★ 连存十次只占一行 —— 否则边输边识别能把表刷爆")
    void consecutiveSavesStayOneRow() {
        for (int i = 0; i < 10; i++) {
            revisions.recordSave(GOODS, MINE, "{\"v\":" + i + "}", "售价", PrdGoodsRevision.SRC_QUICK_TEXT);
        }
        List<GoodsRevisionVO> all = revisions.list(MINE, GOODS);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).revisionNo()).isEqualTo(1);
        // 留下的是最后一次的内容
        assertThat(payloadOf(1)).isEqualTo("{\"v\":9}");
    }

    @Test
    @DisplayName("没有未发布行时发布不补一行 —— 补出来的是看不出来源的快照")
    void publishWithoutPendingDoesNothing() {
        revisions.recordPublished(GOODS, "staff-A");
        assertThat(revisions.list(MINE, GOODS)).isEmpty();
    }

    @Test
    @DisplayName("驳回落在未发布那一行,原因一起存")
    void rejectMarksPending() {
        revisions.recordSave(GOODS, MINE, "{}", "商品图", PrdGoodsRevision.SRC_ZIP);
        revisions.recordRejected(GOODS, "主图含其他平台水印");
        GoodsRevisionVO v = revisions.list(MINE, GOODS).get(0);
        assertThat(v.status()).isEqualTo(PrdGoodsRevision.REJECTED);
        assertThat(v.rejectReason()).isEqualTo("主图含其他平台水印");
    }

    @Test
    @DisplayName("★★★ 别家的商品查不到历史 —— 带域表要绕域,但归属要自己判")
    void othersGoodsNotVisible() {
        revisions.recordSave(GOODS, MINE, "{}", "售价", null);
        assertThatThrownBy(() -> revisions.list(OTHERS, GOODS))
                .hasMessageContaining("NOT_FOUND");
    }

    @Test
    @DisplayName("entrySource 不传落 MANUAL —— 不带它的老调用方不该写进一个 null")
    void nullEntrySourceFallsBackToManual() {
        revisions.recordSave(GOODS, MINE, "{}", null, null);
        assertThat(revisions.list(MINE, GOODS).get(0).entrySource())
                .isEqualTo(PrdGoodsRevision.SRC_MANUAL);
    }

    private int onlineRows() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM prd_goods_revision WHERE goods_no = ? AND status = 'ONLINE'",
                Integer.class, GOODS);
        return n == null ? 0 : n;
    }

    private String payloadOf(int revisionNo) {
        return jdbc.queryForObject(
                "SELECT payload FROM prd_goods_revision WHERE goods_no = ? AND revision_no = ?",
                String.class, GOODS, revisionNo);
    }
}

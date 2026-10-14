package ai.neargo.shop.report.dao;

import java.time.LocalDate;
import java.util.List;

import ai.neargo.shop.report.dto.DailyGoodsRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 商品日汇总 DAO（TDD §5，P2）。两条判据，各对应设计里的一个决定。 */
class ReportDailyGoodsDaoTest {

    private static final LocalDate D1 = LocalDate.of(2026, 9, 28);
    private static final LocalDate D2 = LocalDate.of(2026, 9, 29);

    private ReportDailyGoodsDao dao(String db) {
        return new ReportDailyGoodsDao(ReportDaoTestSupport.freshDb(db));
    }

    @Test
    @DisplayName("★★★ 榜单按 goods_no 跨天合并 —— 不合并的话「近 30 天卖得最多」退化成「某一天卖得最多」")
    void mergesAcrossDays() {
        ReportDailyGoodsDao dao = dao("goods_merge");
        dao.replaceWindow(D1, D2, List.of(
                row(D1, "G-A", 3, 3000, 0),
                row(D2, "G-A", 4, 4000, 0),
                // B 单日比 A 的任一天都多，但两天合起来不如 A
                row(D1, "G-B", 5, 5000, 0)));

        List<DailyGoodsRow> byQty = dao.rank("E1", null, D1, D2, ReportDailyGoodsDao.OrderBy.QTY, 10);

        assertThat(byQty).extracting(DailyGoodsRow::goodsNo).containsExactly("G-A", "G-B");
        assertThat(byQty.get(0).qty()).isEqualTo(7);
        assertThat(byQty.get(0).amountMinor()).isEqualTo(7000);
    }

    @Test
    @DisplayName("★★★ 按销售额排与按件数排给出不同的第一名 —— 否则这两档是同一个实现")
    void orderByChangesTheWinner() {
        ReportDailyGoodsDao dao = dao("goods_order");
        dao.replaceWindow(D1, D1, List.of(
                // 小米椒：卖得多、不值钱
                row(D1, "G-CHILI", 74, 640, 0),
                // 五花肉：卖得少、值钱
                row(D1, "G-PORK", 20, 3120, 0)));

        assertThat(dao.rank("E1", null, D1, D1, ReportDailyGoodsDao.OrderBy.QTY, 1))
                .extracting(DailyGoodsRow::goodsNo).containsExactly("G-CHILI");
        assertThat(dao.rank("E1", null, D1, D1, ReportDailyGoodsDao.OrderBy.AMOUNT, 1))
                .extracting(DailyGoodsRow::goodsNo).containsExactly("G-PORK");
    }

    @Test
    @DisplayName("★★ 赠品不进 qty —— 「送出去 100 件」不是「卖了 100 件」")
    void giftsStayOutOfQty() {
        ReportDailyGoodsDao dao = dao("goods_gift");
        dao.replaceWindow(D1, D1, List.of(row(D1, "G-A", 2, 2000, 100)));

        DailyGoodsRow r = dao.rank("E1", null, D1, D1, ReportDailyGoodsDao.OrderBy.QTY, 1).get(0);

        assertThat(r.qty()).as("赠品混进 qty 的话这里会是 102").isEqualTo(2);
        assertThat(r.giftQty()).isEqualTo(100);
        assertThat(r.amountMinor()).as("赠品价格为 0，不该抬高销售额").isEqualTo(2000);
    }

    private static DailyGoodsRow row(LocalDate d, String goodsNo, int qty, long amount, int gift) {
        return new DailyGoodsRow(d, "E1", "S1", goodsNo, "商品 " + goodsNo, "500g", "C1",
                qty, amount, gift);
    }
}

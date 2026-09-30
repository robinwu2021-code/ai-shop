package ai.neargo.shop.report.dao;

import java.time.LocalDate;
import java.util.List;

import ai.neargo.shop.report.dto.DailyStoreRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 门店日汇总 DAO 的读写语义（TDD-B端报表库与日结 §5）。
 *
 * <p>三条判据，每条对应设计里的一个决定，而不是「覆盖一下方法」。
 */
class ReportDailyStoreDaoTest {

    private static final LocalDate D1 = LocalDate.of(2026, 9, 28);
    private static final LocalDate D2 = LocalDate.of(2026, 9, 29);
    private static final LocalDate D3 = LocalDate.of(2026, 9, 30);

    private ReportDailyStoreDao dao(String db) {
        JdbcClient jdbc = ReportDaoTestSupport.freshDb(db);
        return new ReportDailyStoreDao(jdbc);
    }

    @Test
    @DisplayName("★★★ 前天的退款，今天跑批要能改正前天那一行 —— 这是「不做增量」的全部理由")
    void lateRefundFixesHistory() {
        ReportDailyStoreDao dao = dao("late_refund");

        // 第一次跑：D1 当天还没有退款
        dao.replaceWindow(D1, D3, List.of(row(D1, 10, 1000, 0, 0), row(D2, 8, 800, 0, 0)));
        assertThat(one(dao, D1).refundMinor()).isZero();

        /*
         * 两天后买家退了 D1 那单。日结的窗口仍然盖着 D1，于是这一次重算把 D1 那一行
         * **改正**了 —— 而不是把退款记到今天头上。
         *
         * 换成增量累加的话，D1 那一行会永远停在 refund=0：前天的退款补不回去，
         * 而且不会有任何东西报错，差额一直留在账上。
         */
        dao.replaceWindow(D1, D3, List.of(row(D1, 10, 1000, 1, 300), row(D2, 8, 800, 0, 0)));

        assertThat(one(dao, D1).refundMinor()).isEqualTo(300);
        assertThat(one(dao, D1).refundOrders()).isEqualTo(1);
        // D1 的成交额没被退款改动 —— 退款是另一列，不是把 gmv 减掉
        assertThat(one(dao, D1).gmvMinor()).isEqualTo(1000);
    }

    @Test
    @DisplayName("★★★ 同一个窗口跑三次，结果与跑一次相同（先删后写的幂等）")
    void rerunIsIdempotent() {
        ReportDailyStoreDao dao = dao("idempotent");
        List<DailyStoreRow> rows = List.of(row(D1, 10, 1000, 0, 0), row(D2, 8, 800, 0, 0));

        dao.replaceWindow(D1, D3, rows);
        dao.replaceWindow(D1, D3, rows);
        dao.replaceWindow(D1, D3, rows);

        // 跑三次不应该变成三倍，也不应该多出重复行
        List<DailyStoreRow> all = dao.findRange("E1", null, D1, D3);
        assertThat(all).hasSize(2);
        assertThat(all.stream().mapToLong(DailyStoreRow::gmvMinor).sum()).isEqualTo(1800);
    }

    @Test
    @DisplayName("★★★ 查询漏了 entityNo 就抛错；storeNos 传空集合返回空而不是查全部")
    void scopeIsExplicit() {
        ReportDailyStoreDao dao = dao("scope");
        dao.replaceWindow(D1, D3, List.of(row(D1, 10, 1000, 0, 0), other(D1)));

        // 本库没有数据域拦截器，少一个条件就是越权
        assertThatThrownBy(() -> dao.findRange(null, null, D1, D3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("越权");

        // 「指定了门店范围但范围是空的」＝一家都不能看，不是「看全部」
        assertThat(dao.findRange("E1", List.of(), D1, D3)).isEmpty();

        // 带上 entityNo 只看得到自己的
        assertThat(dao.findRange("E1", null, D1, D3))
                .extracting(DailyStoreRow::entityNo).containsOnly("E1");
    }

    private static DailyStoreRow one(ReportDailyStoreDao dao, LocalDate d) {
        List<DailyStoreRow> rows = dao.findRange("E1", null, d, d);
        assertThat(rows).as("期望 %s 只有一行", d).hasSize(1);
        return rows.get(0);
    }

    private static DailyStoreRow row(LocalDate d, int orders, long gmv, int refundOrders, long refund) {
        return new DailyStoreRow(d, "E1", "S1", orders, gmv, refundOrders, refund,
                orders, 1, 6, gmv / 2, orders, 50, 20, 10, gmv - 80, "CNY");
    }

    /** 另一家商户的行，用来验越权 */
    private static DailyStoreRow other(LocalDate d) {
        return new DailyStoreRow(d, "E2", "S9", 5, 500, 0, 0, 5, 0, 2, 200, 5, 30, 10, 5, 455, "CNY");
    }
}

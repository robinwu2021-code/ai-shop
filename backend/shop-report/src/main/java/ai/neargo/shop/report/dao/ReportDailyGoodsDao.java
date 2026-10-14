package ai.neargo.shop.report.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import ai.neargo.shop.report.dto.DailyGoodsRow;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code rpt_daily_goods} 的读写。写法与 {@link ReportDailyStoreDao} 一致 ——
 * 先删后写、查询强制带 {@code entityNo}，理由见那一个。
 */
public class ReportDailyGoodsDao {

    /** 榜单的排序口径。**不是任意字段** —— 可排的只有这两样，端上传别的落到件数。 */
    public enum OrderBy { QTY, AMOUNT }

    private final JdbcClient jdbc;

    public ReportDailyGoodsDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 重算一个日期窗口：先删后写。幂等与「前天的退款能改正前天那一行」同 {@link ReportDailyStoreDao}。 */
    public int replaceWindow(LocalDate from, LocalDate to, List<DailyGoodsRow> rows) {
        jdbc.sql("DELETE FROM rpt_daily_goods WHERE stat_date BETWEEN ? AND ?")
                .param(from).param(to).update();
        if (rows.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        int n = 0;
        for (DailyGoodsRow r : rows) {
            n += jdbc.sql("""
                    INSERT INTO rpt_daily_goods
                      (stat_date, entity_no, store_no, goods_no, title, spec, category_no,
                       qty, amount_minor, gift_qty,
                       created_at, updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    """)
                    .param(r.statDate()).param(r.entityNo()).param(r.storeNo()).param(r.goodsNo())
                    .param(r.title()).param(r.spec()).param(r.categoryNo())
                    .param(r.qty()).param(r.amountMinor()).param(r.giftQty())
                    .param(now).param(now)
                    .update();
        }
        return n;
    }

    /**
     * 榜单：某商户在一个区间里按件数或销售额排的前 N 个商品。
     *
     * <p><b>按 {@code goods_no} 跨天合并</b> —— 一个商品在区间里有几天就有几行，
     * 不合并的话「最近 30 天卖得最多」会退化成「某一天卖得最多」。
     *
     * @param storeNos 门店范围；{@code null} 表示该商户全部门店
     */
    public List<DailyGoodsRow> rank(String entityNo, Collection<String> storeNos,
                                    LocalDate from, LocalDate to, OrderBy orderBy, int limit) {
        if (entityNo == null || entityNo.isBlank()) {
            throw new IllegalArgumentException(
                    "报表查询必须带 entityNo —— 报表库没有数据域拦截器，漏了就是越权");
        }
        if (storeNos != null && storeNos.isEmpty()) {
            // 「指定了门店范围但范围是空的」＝一家都不能看，不是「看全部」
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                SELECT entity_no, goods_no,
                       MAX(title) AS title, MAX(spec) AS spec, MAX(category_no) AS category_no,
                       SUM(qty) AS qty, SUM(amount_minor) AS amount_minor, SUM(gift_qty) AS gift_qty
                  FROM rpt_daily_goods
                 WHERE entity_no = ? AND stat_date BETWEEN ? AND ?
                """);
        if (storeNos != null) {
            sql.append(" AND store_no IN (")
                    .append(String.join(",", storeNos.stream().map(s -> "?").toList()))
                    .append(")");
        }
        // 名字取 MAX 是因为同一个商品跨天可能改过名 —— 取哪一个都行，但要稳定
        sql.append(" GROUP BY entity_no, goods_no ORDER BY ")
                .append(orderBy == OrderBy.AMOUNT ? "amount_minor" : "qty")
                .append(" DESC LIMIT ?");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString())
                .param(entityNo).param(from).param(to);
        if (storeNos != null) {
            for (String s : storeNos) {
                spec = spec.param(s);
            }
        }
        return spec.param(limit).query(ReportDailyGoodsDao::mapAgg).list();
    }

    /** 聚合后的行：{@code statDate} / {@code storeNo} 已经被合并掉，填占位值。 */
    private static DailyGoodsRow mapAgg(ResultSet rs, int rowNum) throws SQLException {
        return new DailyGoodsRow(
                null, rs.getString("entity_no"), "*", rs.getString("goods_no"),
                rs.getString("title"), rs.getString("spec"), rs.getString("category_no"),
                rs.getInt("qty"), rs.getLong("amount_minor"), rs.getInt("gift_qty"));
    }
}

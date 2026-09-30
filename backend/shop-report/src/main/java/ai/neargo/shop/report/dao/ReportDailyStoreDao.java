package ai.neargo.shop.report.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import ai.neargo.shop.report.dto.DailyStoreRow;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code rpt_daily_store} 的读写。**手写 SQL，不用 repository 代理**（见 ReportDataSourceConfig）。
 *
 * <p><b>本库没有数据域拦截器</b>，所以读方法一律要求传 {@code entityNo}，
 * 且在方法里拒绝空值 —— 漏一个条件在这里不是「查得多一点」，是越权，而且不报错。
 */
public class ReportDailyStoreDao {

    private final JdbcClient jdbc;

    public ReportDailyStoreDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 重算一个日期窗口：**先删后写**。
     *
     * <p>不是 upsert 也不是增量累加。理由写在 TDD §2.3：退款、售后、改价都会改动
     * 历史某一天的数字，增量累加的话前天的一笔退款永远补不回去，而且不会有任何东西报错。
     * 先删后写让「最近几天」始终是对的，代价只是每天多算几天的量。
     *
     * <p><b>幂等由此而来</b>：同一个窗口跑几次结果一样，重跑是安全的。
     *
     * @return 写入的行数
     */
    public int replaceWindow(LocalDate from, LocalDate to, List<DailyStoreRow> rows) {
        jdbc.sql("DELETE FROM rpt_daily_store WHERE stat_date BETWEEN ? AND ?")
                .param(from).param(to).update();
        if (rows.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        int n = 0;
        for (DailyStoreRow r : rows) {
            n += jdbc.sql("""
                    INSERT INTO rpt_daily_store
                      (stat_date, entity_no, store_no, orders, gmv_minor,
                       refund_orders, refund_minor, buyers, new_buyers,
                       owned_orders, owned_gmv_minor, commission_minor, service_fee_minor,
                       freight_income_minor, freight_cost_minor, net_minor, currency,
                       created_at, updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """)
                    .param(r.statDate()).param(r.entityNo()).param(r.storeNo())
                    .param(r.orders()).param(r.gmvMinor())
                    .param(r.refundOrders()).param(r.refundMinor())
                    .param(r.buyers()).param(r.newBuyers())
                    .param(r.ownedOrders()).param(r.ownedGmvMinor())
                    .param(r.commissionMinor()).param(r.serviceFeeMinor())
                    .param(r.freightIncomeMinor()).param(r.freightCostMinor())
                    .param(r.netMinor()).param(r.currency())
                    .param(now).param(now)
                    .update();
        }
        return n;
    }

    /**
     * 某商户在一个区间里的逐日行。
     *
     * @param storeNos 只看这几家店；{@code null} 表示该商户全部门店
     */
    public List<DailyStoreRow> findRange(String entityNo, Collection<String> storeNos,
                                         LocalDate from, LocalDate to) {
        requireEntity(entityNo);
        if (storeNos != null && storeNos.isEmpty()) {
            // 「指定了门店范围但范围是空的」＝一家都不能看，不是「看全部」。
            // 退化成全查是一个典型的越权写法：调用方本来想限制，结果放开了
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                SELECT stat_date, entity_no, store_no, orders, gmv_minor,
                       refund_orders, refund_minor, buyers, new_buyers,
                       owned_orders, owned_gmv_minor, commission_minor, service_fee_minor,
                       freight_income_minor, freight_cost_minor, net_minor, currency
                  FROM rpt_daily_store
                 WHERE entity_no = ? AND stat_date BETWEEN ? AND ?
                """);
        if (storeNos != null) {
            sql.append(" AND store_no IN (")
                    .append(String.join(",", storeNos.stream().map(s -> "?").toList()))
                    .append(")");
        }
        sql.append(" ORDER BY stat_date DESC, store_no");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString())
                .param(entityNo).param(from).param(to);
        if (storeNos != null) {
            for (String s : storeNos) {
                spec = spec.param(s);
            }
        }
        return spec.query(ReportDailyStoreDao::map).list();
    }

    private static void requireEntity(String entityNo) {
        if (entityNo == null || entityNo.isBlank()) {
            // 本库没有行级数据域，少这一句就是「谁都能查全部」
            throw new IllegalArgumentException(
                    "报表查询必须带 entityNo —— 报表库没有数据域拦截器，漏了就是越权");
        }
    }

    private static DailyStoreRow map(ResultSet rs, int rowNum) throws SQLException {
        return new DailyStoreRow(
                rs.getDate("stat_date").toLocalDate(),
                rs.getString("entity_no"),
                rs.getString("store_no"),
                rs.getInt("orders"),
                rs.getLong("gmv_minor"),
                rs.getInt("refund_orders"),
                rs.getLong("refund_minor"),
                rs.getInt("buyers"),
                rs.getInt("new_buyers"),
                rs.getInt("owned_orders"),
                rs.getLong("owned_gmv_minor"),
                rs.getLong("commission_minor"),
                rs.getLong("service_fee_minor"),
                rs.getLong("freight_income_minor"),
                rs.getLong("freight_cost_minor"),
                rs.getLong("net_minor"),
                rs.getString("currency"));
    }
}

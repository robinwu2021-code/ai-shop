package ai.neargo.shop.report.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import ai.neargo.shop.spi.report.ReportContracts.Partition;
import ai.neargo.shop.spi.report.ReportContracts.Scope;
import org.springframework.jdbc.core.simple.JdbcClient;

abstract class ReportDaoSupport {
    static final String SCOPE = "tenant_no=:tenant AND entity_no=:entity AND store_no=:store";
    static final String PARTITION = SCOPE + " AND business_date=:day AND date_basis='BUSINESS_DATE' AND metric_version=:version";
    final JdbcClient jdbc;
    ReportDaoSupport(JdbcClient jdbc) { this.jdbc = jdbc; }
    JdbcClient.StatementSpec scoped(String sql, Scope scope) {
        return jdbc.sql(sql).param("tenant",scope.tenantNo()).param("entity",scope.entityNo()).param("store",scope.storeNo());
    }
    JdbcClient.StatementSpec partitioned(String sql, Partition partition) {
        return scoped(sql,partition.scope()).param("day",partition.businessDate()).param("version",partition.metricVersion());
    }
    static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column,LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
    static LocalDateTime utc(Instant value) { return value == null ? null : LocalDateTime.ofInstant(value,ZoneOffset.UTC); }
    static void one(int count) { if (count != 1) throw new IllegalStateException("REPORT_CONCURRENT_CHANGE"); }
}

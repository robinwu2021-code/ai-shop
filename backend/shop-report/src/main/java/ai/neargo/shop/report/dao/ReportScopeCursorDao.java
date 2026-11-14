package ai.neargo.shop.report.dao;

import java.time.LocalDate;
import java.util.List;

import ai.neargo.shop.report.dto.ReportRows.CursorRow;
import ai.neargo.shop.spi.report.ReportContracts.Partition;
import ai.neargo.shop.spi.report.ReportContracts.Scope;
import org.springframework.jdbc.core.simple.JdbcClient;

public class ReportScopeCursorDao extends ReportDaoSupport {
    public ReportScopeCursorDao(JdbcClient jdbc) { super(jdbc); }
    /** System worker only; public queries must always use find(scope). */
    public List<CursorRow> enabledForWorker(int limit) {
        if (limit<1 || limit>1000) throw new IllegalArgumentException("REPORT_INVALID_LIMIT");
        return jdbc.sql("SELECT * FROM rpt_scope_cursor WHERE enabled=TRUE ORDER BY tenant_no,entity_no,store_no,metric_version LIMIT :limit")
                .param("limit",limit+1).query((rs,n)-> {
                    var scope=new Scope(rs.getString("tenant_no"),rs.getString("entity_no"),rs.getString("store_no"));
                    return new CursorRow(new Partition(scope,rs.getDate("first_stat_date").toLocalDate(),rs.getInt("metric_version")),
                            rs.getDate("first_stat_date").toLocalDate(),rs.getObject("last_planned_date",LocalDate.class),
                            true,rs.getBoolean("default_for_reads"),rs.getLong("version"));
                }).list();
    }
    public void advanceContiguous(Scope scope,int version,LocalDate date) {
        scoped("UPDATE rpt_scope_cursor SET last_planned_date=:day,version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE "+SCOPE
                +" AND metric_version=:version AND enabled=TRUE AND (last_planned_date=DATE_SUB(:day,INTERVAL 1 DAY)"
                +" OR (last_planned_date IS NULL AND first_stat_date=:day))",scope)
                .param("day",date).param("version",version).update();
    }
    public void enable(Scope scope, int version, LocalDate firstDate) {
        scoped("""
                INSERT INTO rpt_scope_cursor(tenant_no,entity_no,store_no,date_basis,metric_version,first_stat_date,
                  enabled,default_for_reads,version,created_at,updated_at)
                VALUES(:tenant,:entity,:store,'BUSINESS_DATE',:version,:first,TRUE,FALSE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE id=id
                """,scope).param("version",version).param("first",firstDate).update();
    }
    public List<CursorRow> find(Scope scope, boolean lock) {
        return scoped("SELECT * FROM rpt_scope_cursor WHERE "+SCOPE+" AND date_basis='BUSINESS_DATE' ORDER BY metric_version"+(lock?" FOR UPDATE":""),scope)
                .query((rs,n) -> new CursorRow(new Partition(scope,rs.getDate("first_stat_date").toLocalDate(),rs.getInt("metric_version")),
                        rs.getDate("first_stat_date").toLocalDate(),rs.getObject("last_planned_date",LocalDate.class),rs.getBoolean("enabled"),
                        rs.getBoolean("default_for_reads"),rs.getLong("version"))).list();
    }
    public void planned(Scope scope, int version, LocalDate through) {
        one(scoped("UPDATE rpt_scope_cursor SET last_planned_date=:through,version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE "+SCOPE
                +" AND date_basis='BUSINESS_DATE' AND metric_version=:version AND enabled=TRUE",scope)
                .param("through",through).param("version",version).update());
    }
    public void defaultVersion(Scope scope, int version) {
        scoped("UPDATE rpt_scope_cursor SET default_for_reads=FALSE,updated_at=UTC_TIMESTAMP(6) WHERE "+SCOPE
                +" AND date_basis='BUSINESS_DATE' AND default_for_reads=TRUE",scope).update();
        one(scoped("UPDATE rpt_scope_cursor SET default_for_reads=TRUE,version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE "+SCOPE
                +" AND date_basis='BUSINESS_DATE' AND metric_version=:version AND enabled=TRUE",scope).param("version",version).update());
    }
}

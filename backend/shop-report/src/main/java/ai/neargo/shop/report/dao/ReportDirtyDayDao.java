package ai.neargo.shop.report.dao;

import java.time.Instant;
import java.util.List;

import ai.neargo.shop.report.dto.ReportRows.DirtyRow;
import ai.neargo.shop.spi.report.ReportContracts.Partition;
import ai.neargo.shop.spi.report.ReportContracts.Scope;
import org.springframework.jdbc.core.simple.JdbcClient;

public class ReportDirtyDayDao extends ReportDaoSupport {
    public ReportDirtyDayDao(JdbcClient jdbc) { super(jdbc); }
    public void mark(Partition p, String event, String reason, Instant readyAfter) {
        partitioned("""
                INSERT INTO rpt_dirty_day(tenant_no,entity_no,store_no,business_date,date_basis,metric_version,
                  dirty_generation,acked_generation,first_event_ref,last_event_ref,reason,ready_after,version,created_at,updated_at)
                VALUES(:tenant,:entity,:store,:day,'BUSINESS_DATE',:version,1,0,:event,:event,:reason,:ready,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE dirty_generation=dirty_generation+1,last_event_ref=:event,reason=:reason,
                  ready_after=GREATEST(ready_after,:ready),version=version+1,updated_at=UTC_TIMESTAMP(6)
                """,p).param("event",event).param("reason",reason).param("ready",utc(readyAfter)).update();
    }
    public void acknowledge(Partition p, long captured) {
        partitioned("UPDATE rpt_dirty_day SET acked_generation=GREATEST(acked_generation,LEAST(dirty_generation,:captured)),"
                +"updated_at=UTC_TIMESTAMP(6) WHERE "+PARTITION,p).param("captured",captured).update();
    }
    public List<DirtyRow> pending(Scope scope, int version, int limit) {
        return scoped("SELECT * FROM rpt_dirty_day WHERE "+SCOPE+" AND metric_version=:version AND date_basis='BUSINESS_DATE'"
                +" AND dirty_generation>acked_generation AND ready_after<=UTC_TIMESTAMP(6) ORDER BY business_date LIMIT :limit",scope)
                .param("version",version).param("limit",limit).query((rs,n) -> new DirtyRow(new Partition(scope,
                        rs.getDate("business_date").toLocalDate(),version),rs.getLong("dirty_generation"),rs.getLong("acked_generation"),instant(rs,"ready_after"))).list();
    }
}

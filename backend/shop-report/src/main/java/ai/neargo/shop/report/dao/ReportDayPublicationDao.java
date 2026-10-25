package ai.neargo.shop.report.dao;

import java.time.Instant;
import java.util.Optional;

import ai.neargo.shop.report.dto.ReportRows.PublicationRow;
import ai.neargo.shop.spi.report.ReportContracts.BuildLease;
import ai.neargo.shop.spi.report.ReportContracts.Partition;
import org.springframework.jdbc.core.simple.JdbcClient;

public class ReportDayPublicationDao extends ReportDaoSupport {
    public ReportDayPublicationDao(JdbcClient jdbc) { super(jdbc); }
    public void ensure(Partition p) {
        partitioned("""
                INSERT INTO rpt_day_publication(tenant_no,entity_no,store_no,business_date,date_basis,metric_version,created_at,updated_at)
                VALUES(:tenant,:entity,:store,:day,'BUSINESS_DATE',:version,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE id=id
                """,p).update();
    }
    public Optional<PublicationRow> find(Partition p, boolean lock) {
        return partitioned("SELECT *,UTC_TIMESTAMP(6) database_now FROM rpt_day_publication WHERE " + PARTITION
                + (lock ? " FOR UPDATE" : ""),p).query((rs,n) -> new PublicationRow(p,rs.getString("active_build_no"),
                rs.getObject("active_attempt_no",Long.class),rs.getLong("generation"),rs.getLong("current_fence"),
                rs.getString("lease_owner"),instant(rs,"lease_until"),instant(rs,"published_at"),instant(rs,"database_now"))).optional();
    }
    public void claim(Partition p, long oldFence, String owner, Instant until) {
        one(partitioned("UPDATE rpt_day_publication SET current_fence=current_fence+1,lease_owner=:owner,lease_until=:until,"
                + "updated_at=UTC_TIMESTAMP(6) WHERE " + PARTITION + " AND current_fence=:fence",p)
                .param("owner",owner).param("until",utc(until)).param("fence",oldFence).update());
    }
    public void publish(BuildLease lease, String coverage) {
        one(partitioned("""
                UPDATE rpt_day_publication SET active_build_no=:build,active_attempt_no=:attempt,
                    generation=generation+1,published_at=UTC_TIMESTAMP(6),coverage_summary=:coverage,
                    lease_owner=NULL,lease_until=NULL,updated_at=UTC_TIMESTAMP(6)
                WHERE
                """+PARTITION+" AND current_fence=:fence AND lease_owner=:owner AND lease_until>UTC_TIMESTAMP(6) AND generation=:generation",lease.partition())
                .param("build",lease.buildNo()).param("attempt",lease.attemptNo()).param("coverage",coverage)
                .param("fence",lease.fence()).param("owner",lease.leaseOwner()).param("generation",lease.expectedGeneration()).update());
    }
    public void release(BuildLease lease) {
        partitioned("UPDATE rpt_day_publication SET lease_owner=NULL,lease_until=NULL,updated_at=UTC_TIMESTAMP(6) WHERE "
                +PARTITION+" AND current_fence=:fence AND lease_owner=:owner",lease.partition())
                .param("fence",lease.fence()).param("owner",lease.leaseOwner()).update();
    }
}

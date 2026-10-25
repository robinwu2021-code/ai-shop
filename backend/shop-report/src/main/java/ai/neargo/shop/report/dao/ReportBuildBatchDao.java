package ai.neargo.shop.report.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import ai.neargo.shop.spi.report.ReportContracts.BuildProblem;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import ai.neargo.shop.report.dto.ReportRows.BuildRow;
import ai.neargo.shop.report.support.ReportEvidenceCodec;
import ai.neargo.shop.spi.report.ReportContracts.BuildLease;
import ai.neargo.shop.spi.report.ReportContracts.BuildReq;
import ai.neargo.shop.spi.report.ReportContracts.BuildStatus;
import ai.neargo.shop.spi.report.ReportContracts.DayEvidence;
import ai.neargo.shop.spi.report.ReportContracts.Partition;
import ai.neargo.shop.spi.report.ReportContracts.Scope;
import ai.neargo.shop.spi.report.ReportContracts.SourceKey;
import org.springframework.jdbc.core.simple.JdbcClient;

public class ReportBuildBatchDao extends ReportDaoSupport {
    public ReportBuildBatchDao(JdbcClient jdbc) { super(jdbc); }
    public List<BuildProblem> problems(Scope scope,LocalDate from,LocalDate to,int version,int limit) {
        return scoped("SELECT * FROM rpt_build_batch WHERE "+SCOPE
                +" AND business_date>=:from AND business_date<:to AND metric_version=:version AND status<>'PUBLISHED'"
                +" ORDER BY business_date,created_at LIMIT :limit",scope).param("from",from).param("to",to).param("version",version).param("limit",limit+1)
                .query((rs,n)->new BuildProblem(new Partition(scope,rs.getDate("business_date").toLocalDate(),rs.getInt("metric_version")),
                        rs.getString("build_no"),BuildStatus.valueOf(rs.getString("status")),rs.getString("error_code"),
                        instant(rs,"next_retry_at"),instant(rs,"lease_until"))).list();
    }
    public List<BuildReq> recoverable(Scope scope,int version,int limit) {
        if (limit<1 || limit>1000) throw new IllegalArgumentException("REPORT_INVALID_LIMIT");
        return scoped("SELECT * FROM rpt_build_batch WHERE "+SCOPE+" AND metric_version=:version AND "
                +" ((status='FAILED' AND next_retry_at<=UTC_TIMESTAMP(6)) OR"
                +" (status IN ('RUNNING','VALIDATING') AND lease_until<=UTC_TIMESTAMP(6)))"
                +" ORDER BY business_date,created_at LIMIT :limit",scope)
                .param("version",version).param("limit",limit).query(ReportBuildBatchDao::map).list()
                .stream().map(BuildRow::request).toList();
    }
    public Optional<BuildRow> byRequest(Scope scope, String key, boolean lock) {
        return scoped("SELECT * FROM rpt_build_batch WHERE "+SCOPE+" AND request_key=:key"+(lock?" FOR UPDATE":""),scope)
                .param("key",key).query(ReportBuildBatchDao::map).optional();
    }
    public Optional<BuildRow> byNo(Scope scope, String build, boolean lock) {
        return scoped("SELECT * FROM rpt_build_batch WHERE "+SCOPE+" AND build_no=:build"+(lock?" FOR UPDATE":""),scope)
                .param("build",build).query(ReportBuildBatchDao::map).optional();
    }
    public void insert(BuildReq req, BuildLease lease) {
        partitioned("""
                INSERT INTO rpt_build_batch(tenant_no,entity_no,store_no,business_date,date_basis,metric_version,
                  build_no,actor_no,request_key,request_hash,reason,job_run_id,attempt_no,fence,expected_generation,
                  lease_owner,lease_until,status,spec_hash,required_sources,day_evidence,captured_dirty_generation,
                  started_at,created_at,updated_at)
                VALUES(:tenant,:entity,:store,:day,'BUSINESS_DATE',:version,:build,:actor,:key,:hash,:reason,:run,
                  :attempt,:fence,:generation,:owner,:until,'RUNNING',:spec,:required,:evidence,:dirty,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """,req.partition()).param("build",lease.buildNo()).param("actor",req.actorNo()).param("key",req.requestKey())
                .param("hash",lease.requestHash()).param("reason",req.reason()).param("run",req.jobRunId())
                .param("attempt",lease.attemptNo()).param("fence",lease.fence()).param("generation",lease.expectedGeneration())
                .param("owner",lease.leaseOwner()).param("until",utc(lease.leaseUntil())).param("spec",req.specHash())
                .param("required",ReportEvidenceCodec.json(req.requiredSources())).param("evidence",ReportEvidenceCodec.json(req.day()))
                .param("dirty",req.dirtyGeneration()).update();
    }
    public void reclaim(BuildLease lease, long oldAttempt) {
        one(scoped("""
                UPDATE rpt_build_batch SET attempt_no=:attempt,fence=:fence,expected_generation=:generation,
                  lease_owner=:owner,lease_until=:until,status='RUNNING',validation_hash=NULL,
                  failure_kind=NULL,error_code=NULL,next_retry_at=NULL,started_at=UTC_TIMESTAMP(6),finished_at=NULL,
                  updated_at=UTC_TIMESTAMP(6) WHERE
                """+SCOPE+" AND build_no=:build AND attempt_no=:old AND status<>'PUBLISHED'",lease.partition().scope())
                .param("attempt",lease.attemptNo()).param("fence",lease.fence()).param("generation",lease.expectedGeneration())
                .param("owner",lease.leaseOwner()).param("until",utc(lease.leaseUntil())).param("build",lease.buildNo())
                .param("old",oldAttempt).update());
    }
    public void staged(BuildLease lease, String hash) {
        one(owned("UPDATE rpt_build_batch SET validation_hash=:hash,status='VALIDATING',updated_at=UTC_TIMESTAMP(6) WHERE ",lease)
                .param("hash",hash).update());
    }
    public void published(BuildLease lease, Instant publishedAt) {
        one(owned("UPDATE rpt_build_batch SET status='PUBLISHED',finished_at=:published,updated_at=UTC_TIMESTAMP(6) WHERE ",lease)
                .param("published",utc(publishedAt)).update());
    }
    public void failed(BuildLease lease, String code, Instant retryAt) {
        one(owned("UPDATE rpt_build_batch SET status='FAILED',error_code=:code,next_retry_at=:retry,updated_at=UTC_TIMESTAMP(6) WHERE ",lease)
                .param("code",code).param("retry",utc(retryAt)).update());
    }
    private JdbcClient.StatementSpec owned(String sql, BuildLease lease) {
        return scoped(sql+SCOPE+" AND build_no=:build AND attempt_no=:attempt AND fence=:fence AND lease_owner=:owner AND status IN ('RUNNING','VALIDATING')",lease.partition().scope())
                .param("build",lease.buildNo()).param("attempt",lease.attemptNo()).param("fence",lease.fence()).param("owner",lease.leaseOwner());
    }
    private static BuildRow map(ResultSet rs, int n) throws SQLException {
        var scope = new Scope(rs.getString("tenant_no"),rs.getString("entity_no"),rs.getString("store_no"));
        var partition = new Partition(scope,rs.getDate("business_date").toLocalDate(),rs.getInt("metric_version"));
        var req = new BuildReq(partition,ReportEvidenceCodec.read(rs.getString("day_evidence"),DayEvidence.class),
                rs.getString("actor_no"),rs.getString("request_key"),rs.getString("spec_hash"),
                Set.copyOf(Arrays.asList(ReportEvidenceCodec.read(rs.getString("required_sources"),SourceKey[].class))),
                rs.getLong("captured_dirty_generation"),rs.getString("reason"),rs.getString("job_run_id"));
        return new BuildRow(req,rs.getString("build_no"),rs.getString("request_hash"),rs.getLong("attempt_no"),rs.getLong("fence"),
                rs.getLong("expected_generation"),rs.getString("lease_owner"),instant(rs,"lease_until"),
                BuildStatus.valueOf(rs.getString("status")),rs.getString("validation_hash"),instant(rs,"finished_at"));
    }
}

package ai.neargo.shop.report.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import ai.neargo.shop.report.dto.ReportExportTaskRow;
import ai.neargo.shop.report.support.ReportEvidenceCodec;
import ai.neargo.shop.spi.report.ReportExportPort.Reference;
import ai.neargo.shop.spi.report.ReportExportPort.Snapshot;
import ai.neargo.shop.spi.report.ReportExportPort.Status;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Every actor-facing read includes tenant/entity/actor; maintenance is a separately scoped worker operation. */
public final class ReportExportTaskDao {
    private final JdbcClient jdbc;
    private static final String OWNER="tenant_no=:tenant AND entity_no=:entity AND actor_no=:actor";
    public ReportExportTaskDao(JdbcClient jdbc) { this.jdbc=jdbc; }
    private JdbcClient.StatementSpec scoped(String sql,Reference ref) {
        return jdbc.sql(sql).param("tenant",ref.tenantNo()).param("entity",ref.entityNo()).param("actor",ref.actorNo());
    }
    public Optional<ReportExportTaskRow> find(Reference ref,boolean lock) {
        return scoped("SELECT *,UTC_TIMESTAMP(6) database_now FROM rpt_export_task WHERE "+OWNER+" AND export_no=:export"+(lock?" FOR UPDATE":""),ref)
                .param("export",ref.exportNo()).query(ReportExportTaskDao::map).optional();
    }
    public Optional<ReportExportTaskRow> byRequest(Reference owner,String key,boolean lock) {
        return scoped("SELECT *,UTC_TIMESTAMP(6) database_now FROM rpt_export_task WHERE "+OWNER+" AND request_key=:key"+(lock?" FOR UPDATE":""),owner)
                .param("key",key).query(ReportExportTaskDao::map).optional();
    }
    public void insert(Reference ref,String key,String requestHash,Snapshot snapshot,String snapshotHash) {
        var stores=snapshot.query().scopes().stream().map(s->s.storeNo()).sorted().toList();
        scoped("""
                INSERT INTO rpt_export_task(tenant_no,entity_no,actor_no,export_no,authorized_store_nos,scope_hash,
                  request_key,request_hash,query_snapshot,query_hash,build_refs,format,status,retired_objects,
                  expires_at,created_at,updated_at)
                VALUES(:tenant,:entity,:actor,:export,:stores,:scopeHash,:key,:hash,:snapshot,:snapshotHash,:builds,
                  'CSV','QUEUED','[]',DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 24 HOUR),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """,ref).param("export",ref.exportNo()).param("stores",ReportEvidenceCodec.json(stores))
                .param("scopeHash",ReportEvidenceCodec.hash(snapshot.query().scopes())).param("key",key).param("hash",requestHash)
                .param("snapshot",ReportEvidenceCodec.json(snapshot)).param("snapshotHash",snapshotHash)
                .param("builds",ReportEvidenceCodec.json(snapshot.result().builds())).update();
    }
    public void claim(ReportExportTaskRow row,String worker,String object,String runId,List<String> retired) {
        ReportDaoSupport.one(scoped("""
                UPDATE rpt_export_task SET status='RUNNING',attempt_no=attempt_no+1,lease_owner=:worker,
                  lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 2 MINUTE),object_ref=:object,retired_objects=:retired,
                  execution_ref=:run,error_code=NULL,updated_at=UTC_TIMESTAMP(6) WHERE
                """+OWNER+" AND export_no=:export AND attempt_no=:attempt AND expires_at>UTC_TIMESTAMP(6)",row.reference())
                .param("export",row.reference().exportNo()).param("attempt",row.attempt()).param("worker",worker)
                .param("object",object).param("retired",ReportEvidenceCodec.json(retired)).param("run",runId).update());
    }
    public void succeeded(ReportExportTaskRow row,String checksum,long count) {
        ReportDaoSupport.one(owned("UPDATE rpt_export_task SET status='SUCCEEDED',checksum=:checksum,row_count=:count,"
                +"lease_owner=NULL,lease_until=NULL,updated_at=UTC_TIMESTAMP(6) WHERE ",row)
                .param("checksum",checksum).param("count",count).update());
    }
    public void failed(ReportExportTaskRow row) {
        owned("UPDATE rpt_export_task SET status='FAILED',error_code='REPORT_EXPORT_FAILED',lease_owner=NULL,lease_until=NULL,updated_at=UTC_TIMESTAMP(6) WHERE ",row).update();
    }
    private JdbcClient.StatementSpec owned(String prefix,ReportExportTaskRow row) {
        return scoped(prefix+OWNER+" AND export_no=:export AND attempt_no=:attempt AND lease_owner=:worker"
                +" AND status='RUNNING' AND lease_until>UTC_TIMESTAMP(6) AND expires_at>UTC_TIMESTAMP(6)",row.reference())
                .param("export",row.reference().exportNo()).param("attempt",row.attempt()).param("worker",row.leaseOwner());
    }
    public List<ReportExportTaskRow> maintenance(String tenant,String entity,int limit) {
        if (limit<1 || limit>100) throw new IllegalArgumentException("REPORT_INVALID_LIMIT");
        return jdbc.sql("""
                SELECT *,UTC_TIMESTAMP(6) database_now FROM rpt_export_task WHERE tenant_no=:tenant AND entity_no=:entity
                AND ((expires_at<=UTC_TIMESTAMP(6) AND (last_cleanup_at IS NULL OR last_cleanup_at<DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 DAY)))
                OR (expires_at>UTC_TIMESTAMP(6) AND attempt_no<3 AND (status IN ('QUEUED','FAILED')
                  OR (status='RUNNING' AND lease_until<=UTC_TIMESTAMP(6)))))
                ORDER BY last_cleanup_at,created_at LIMIT :limit
                """).param("tenant",tenant).param("entity",entity).param("limit",limit).query(ReportExportTaskDao::map).list();
    }
    public void expired(Reference ref) {
        scoped("UPDATE rpt_export_task SET status='EXPIRED',last_cleanup_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6) WHERE "+OWNER
                +" AND export_no=:export AND expires_at<=UTC_TIMESTAMP(6)",ref).param("export",ref.exportNo()).update();
    }
    private static ReportExportTaskRow map(ResultSet rs,int n) throws SQLException {
        return new ReportExportTaskRow(new Reference(rs.getString("tenant_no"),rs.getString("entity_no"),rs.getString("actor_no"),rs.getString("export_no")),
                rs.getString("request_key"),rs.getString("request_hash"),rs.getString("query_snapshot"),rs.getString("query_hash"),
                Status.valueOf(rs.getString("status")),rs.getLong("attempt_no"),rs.getString("lease_owner"),ReportDaoSupport.instant(rs,"lease_until"),
                rs.getString("object_ref"),ReportEvidenceCodec.strings(rs.getString("retired_objects")),rs.getString("checksum"),
                rs.getObject("row_count",Long.class),ReportDaoSupport.instant(rs,"expires_at"),ReportDaoSupport.instant(rs,"database_now"));
    }
}

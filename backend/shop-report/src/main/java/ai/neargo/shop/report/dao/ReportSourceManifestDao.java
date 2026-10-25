package ai.neargo.shop.report.dao;

import java.util.List;

import ai.neargo.shop.report.support.ReportEvidenceCodec;
import ai.neargo.shop.spi.report.ReportContracts.BuildLease;
import ai.neargo.shop.spi.report.ReportContracts.Coverage;
import ai.neargo.shop.spi.report.ReportContracts.Owner;
import ai.neargo.shop.spi.report.ReportContracts.Partition;
import ai.neargo.shop.spi.report.ReportContracts.SourceKey;
import ai.neargo.shop.spi.report.ReportContracts.SourceSnapshot;
import org.springframework.jdbc.core.simple.JdbcClient;

public class ReportSourceManifestDao extends ReportDaoSupport {
    public ReportSourceManifestDao(JdbcClient jdbc) { super(jdbc); }
    public void insert(BuildLease lease, SourceSnapshot source) {
        scoped("""
                INSERT INTO rpt_source_manifest(tenant_no,entity_no,store_no,build_no,attempt_no,owner_code,family_code,
                  source_snapshot_ref,source_version,as_of_at,row_count,checksum,completeness,unknown_reason,
                  source_evidence,created_at,updated_at)
                VALUES(:tenant,:entity,:store,:build,:attempt,:owner,:family,:ref,:version,:asof,:count,:checksum,:coverage,
                  :reason,:evidence,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """,lease.partition().scope()).param("build",lease.buildNo()).param("attempt",lease.attemptNo())
                .param("owner",source.source().owner().name()).param("family",source.source().family()).param("ref",source.snapshotRef())
                .param("version",source.sourceVersion()).param("asof",utc(source.asOfAt())).param("count",source.rowCount())
                .param("checksum",source.checksum()).param("coverage",source.coverage().name()).param("reason",source.unknownReason())
                .param("evidence",ReportEvidenceCodec.json(source.sourceRefs())).update();
    }
    public List<SourceSnapshot> find(Partition p, String build, long attempt) {
        return scoped("SELECT * FROM rpt_source_manifest WHERE "+SCOPE+" AND build_no=:build AND attempt_no=:attempt ORDER BY owner_code,family_code",p.scope())
                .param("build",build).param("attempt",attempt).query((rs,n) -> new SourceSnapshot(p,
                        new SourceKey(Owner.valueOf(rs.getString("owner_code")),rs.getString("family_code")),
                        rs.getString("source_snapshot_ref"),rs.getString("source_version"),instant(rs,"as_of_at"),
                        rs.getLong("row_count"),rs.getString("checksum"),Coverage.valueOf(rs.getString("completeness")),
                        rs.getString("unknown_reason"),List.of(),ReportEvidenceCodec.strings(rs.getString("source_evidence")))).list();
    }
}

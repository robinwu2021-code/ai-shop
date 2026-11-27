package ai.neargo.shop.platform.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.approval.LightApprovalPort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@link LightApprovalPort} 的唯一实现，表 {@code sys_approval}（基础能力库；句柄落地前在主库，用主库的 {@link JdbcTemplate}）。
 *
 * <p>每个方法各自一条写语句，不依赖调用方事务：业务库与本表可能不同库，业务事务回滚时这里的审批单可能留下 ——
 * 留下的只是一张没有业务对象的申请，业务按对象读 {@link #latest} 时读不到它，不会误放行。
 */
@Component
public class LightApprovalPortImpl implements LightApprovalPort {

    private static final String COLUMNS = "approval_no,subject_type,subject_ref,revision,status,approval_mode,"
            + "submitted_by,approver_no,decided_at,expires_at,version";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public LightApprovalPortImpl(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    LightApprovalPortImpl(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Approval submit(Submit s) {
        Optional<Approval> existing = byRevision(s.tenantNo(), s.subjectType(), s.subjectRef(), s.revision());
        if (existing.isPresent()) {
            return existing.get();
        }
        Timestamp now = Timestamp.from(clock.instant());
        String no = BizKey.next(BizKey.LIGHT_APPROVAL);
        try {
            jdbc.update("INSERT INTO sys_approval (approval_no,tenant_no,entity_no,store_no,process_code,subject_type,"
                            + "subject_ref,revision,amount_minor,currency,reason_code,reason,submitted_by,approval_mode,"
                            + "approved_via,status,approver_no,decided_at,expires_at,created_at,updated_at,version)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)",
                    no, s.tenantNo(), s.entityNo(), s.storeNo(), s.processCode(), s.subjectType(), s.subjectRef(),
                    s.revision(), s.amountMinor(), s.currency(), s.reasonCode(), s.reason(), s.submittedBy(),
                    s.autoApproved() ? MODE_AUTO : MODE_MANUAL, s.autoApproved() ? s.via() : null,
                    s.autoApproved() ? APPROVED : PENDING, s.autoApproved() ? s.submittedBy() : null,
                    s.autoApproved() ? now : null, s.expiresAt() == null ? null : Timestamp.from(s.expiresAt()),
                    now, now);
        } catch (DuplicateKeyException concurrent) {
            // 同一修订并发送审：唯一键兜底，返回先到的那张
            return byRevision(s.tenantNo(), s.subjectType(), s.subjectRef(), s.revision()).orElseThrow();
        }
        return byNo(no).orElseThrow();
    }

    @Override
    public Approval decide(String approvalNo, Decision d) {
        Approval a = byNo(approvalNo).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        String to = d.approve() ? APPROVED : REJECTED;
        if (to.equals(a.status()) && Objects.equals(d.approverNo(), a.approverNo())) {
            return a;
        }
        Instant now = clock.instant();
        if (!PENDING.equals(a.status()) || Objects.equals(d.approverNo(), a.submittedBy())
                || a.expiresAt() != null && !now.isBefore(a.expiresAt())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        int changed = jdbc.update("UPDATE sys_approval SET status=?,approver_no=?,approved_via=?,decided_at=?,"
                        + "decision_comment=?,updated_at=?,version=version+1 WHERE approval_no=? AND status=?",
                to, d.approverNo(), d.via(), Timestamp.from(now), d.comment(), Timestamp.from(now), approvalNo, PENDING);
        if (changed != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return byNo(approvalNo).orElseThrow();
    }

    @Override
    public Optional<Approval> latest(String tenantNo, String subjectType, String subjectRef) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sys_approval WHERE tenant_no=? AND subject_type=? AND subject_ref=?"
                        + " ORDER BY revision DESC LIMIT 1", LightApprovalPortImpl::row, tenantNo, subjectType, subjectRef)
                .stream().findFirst();
    }

    private Optional<Approval> byRevision(String tenantNo, String subjectType, String subjectRef, long revision) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sys_approval WHERE tenant_no=? AND subject_type=? AND subject_ref=?"
                + " AND revision=?", LightApprovalPortImpl::row, tenantNo, subjectType, subjectRef, revision).stream().findFirst();
    }

    private Optional<Approval> byNo(String approvalNo) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sys_approval WHERE approval_no=?", LightApprovalPortImpl::row,
                approvalNo).stream().findFirst();
    }

    private static Approval row(ResultSet rs, int i) throws SQLException {
        return new Approval(rs.getString("approval_no"), rs.getString("subject_type"), rs.getString("subject_ref"),
                rs.getLong("revision"), rs.getString("status"), rs.getString("approval_mode"),
                rs.getString("submitted_by"), rs.getString("approver_no"), instant(rs.getTimestamp("decided_at")),
                instant(rs.getTimestamp("expires_at")), rs.getLong("version"));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}

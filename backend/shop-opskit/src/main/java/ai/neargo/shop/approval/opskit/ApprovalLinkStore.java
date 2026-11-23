package ai.neargo.shop.approval.opskit;

import ai.neargo.shop.spi.approval.ApprovalPort.Link;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code approval_link}（V690）的读写。状态是 OpsKit 的投影：
 * PENDING_SUBMIT（待投递）→ PENDING（已送审）→ APPLIED / APPROVED / REJECTED / WITHDRAWN / CANCELLED；
 * 投递被 OpsKit 拒绝（非重试类错误）为 SUBMIT_FAILED。
 */
public class ApprovalLinkStore {

    public static final String PENDING_SUBMIT = "PENDING_SUBMIT";
    public static final String PENDING = "PENDING";
    public static final String SUBMIT_FAILED = "SUBMIT_FAILED";

    private static final String COLS =
            "subject_type,subject_ref,revision,process_code,approval_no,instance_no,status,reason,submitted_by,submitted_name";

    private final JdbcTemplate db;

    public ApprovalLinkStore(JdbcTemplate db) {
        this.db = db;
    }

    /** 唯一键冲突 = 同一修订已经送过审，幂等返回 false。 */
    public boolean insertIfAbsent(String subjectType, String subjectRef, long revision, String processCode,
                                  String submittedBy, String submittedName) {
        try {
            return db.update("INSERT INTO approval_link(subject_type,subject_ref,revision,process_code,status,submitted_by,submitted_name,created_at,updated_at)"
                            + " VALUES(?,?,?,?,?,?,?,NOW(),NOW())",
                    subjectType, subjectRef, revision, processCode, PENDING_SUBMIT, submittedBy, submittedName) == 1;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public Optional<Row> find(String subjectType, String subjectRef, long revision) {
        return db.query("SELECT " + COLS + " FROM approval_link WHERE subject_type=? AND subject_ref=? AND revision=?",
                ApprovalLinkStore::row, subjectType, subjectRef, revision).stream().findFirst();
    }

    public Optional<Row> latest(String subjectType, String subjectRef) {
        return db.query("SELECT " + COLS + " FROM approval_link WHERE subject_type=? AND subject_ref=? ORDER BY revision DESC LIMIT 1",
                ApprovalLinkStore::row, subjectType, subjectRef).stream().findFirst();
    }

    /** 按 OpsKit 实例号找；回调偶尔早于送审回填时，退回到该对象最新一条未结束的记录。 */
    public Optional<Row> forInstance(String instanceNo, String subjectType, String subjectRef) {
        var hit = db.query("SELECT " + COLS + " FROM approval_link WHERE instance_no=?", ApprovalLinkStore::row, instanceNo)
                .stream().findFirst();
        if (hit.isPresent()) {
            return hit;
        }
        return db.query("SELECT " + COLS + " FROM approval_link WHERE subject_type=? AND subject_ref=? AND status IN (?,?)"
                        + " ORDER BY revision DESC LIMIT 1",
                ApprovalLinkStore::row, subjectType, subjectRef, PENDING_SUBMIT, PENDING).stream().findFirst();
    }

    public void markSubmitted(String subjectType, String subjectRef, long revision, String approvalNo, String instanceNo) {
        db.update("UPDATE approval_link SET status=?,approval_no=?,instance_no=?,updated_at=NOW()"
                        + " WHERE subject_type=? AND subject_ref=? AND revision=? AND status=?",
                PENDING, approvalNo, instanceNo, subjectType, subjectRef, revision, PENDING_SUBMIT);
    }

    public void markSubmitFailed(String subjectType, String subjectRef, long revision, String reason) {
        db.update("UPDATE approval_link SET status=?,reason=?,updated_at=NOW() WHERE subject_type=? AND subject_ref=? AND revision=? AND status=?",
                SUBMIT_FAILED, cut(reason), subjectType, subjectRef, revision, PENDING_SUBMIT);
    }

    /** 结束：只从未结束的状态推进，重复回调不改写。 */
    public boolean close(String subjectType, String subjectRef, long revision, String instanceNo, String status, String reason) {
        return db.update("UPDATE approval_link SET status=?,reason=?,instance_no=COALESCE(instance_no,?),updated_at=NOW()"
                        + " WHERE subject_type=? AND subject_ref=? AND revision=? AND status IN (?,?)",
                status, cut(reason), instanceNo, subjectType, subjectRef, revision, PENDING_SUBMIT, PENDING) == 1;
    }

    /** 对账候选：送审后超过 {@code olderThanHours} 小时仍未结束的。 */
    public java.util.List<Row> stalePending(int olderThanHours, int limit) {
        return db.query("SELECT " + COLS + " FROM approval_link WHERE status IN (?,?) AND updated_at < DATE_SUB(NOW(), INTERVAL ? HOUR)"
                        + " ORDER BY updated_at LIMIT ?",
                ApprovalLinkStore::row, PENDING_SUBMIT, PENDING, olderThanHours, limit);
    }

    private static String cut(String s) {
        return s == null || s.length() <= 512 ? s : s.substring(0, 512);
    }

    private static Row row(ResultSet r, int i) throws SQLException {
        return new Row(r.getString(1), r.getString(2), r.getLong(3), r.getString(4), r.getString(5), r.getString(6),
                r.getString(7), r.getString(8), r.getString(9), r.getString(10));
    }

    public record Row(String subjectType, String subjectRef, long revision, String processCode, String approvalNo,
                      String instanceNo, String status, String reason, String submittedBy, String submittedName) {
        public Link toLink() {
            return new Link(subjectType, subjectRef, revision, processCode, approvalNo, instanceNo, status, reason);
        }
    }
}

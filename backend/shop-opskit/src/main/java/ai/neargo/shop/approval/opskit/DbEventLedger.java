package ai.neargo.shop.approval.opskit;

import ai.neargo.opskit.connector.EventLedger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * OpsKit 回调的去重台账，复用 {@code sys_event_consumed}（唯一键 event_no + handler）。
 * event_no 只有 64 位，所以存 {@code SHA-256(eventType + ' ' + idemKey)} 的十六进制。
 *
 * <p>台账与业务执行不在同一事务（client 先记台账、再执行、失败再 {@link #forget}）：
 * 执行成功但回复丢失时 OpsKit 会重投，这时台账已被撤销、业务再执行一次 —— 所以执行动作本身必须幂等
 * （{@code ApprovalSubject.apply} 的约定）。
 */
public class DbEventLedger implements EventLedger {

    static final String HANDLER = "opskit-webhook";

    private final JdbcTemplate db;

    public DbEventLedger(JdbcTemplate db) {
        this.db = db;
    }

    @Override
    public boolean firstTime(String eventType, String idemKey) {
        try {
            return db.update("INSERT INTO sys_event_consumed(event_no,handler,consumed_at) VALUES(?,?,NOW())",
                    key(eventType, idemKey), HANDLER) == 1;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public void forget(String eventType, String idemKey) {
        db.update("DELETE FROM sys_event_consumed WHERE event_no=? AND handler=?", key(eventType, idemKey), HANDLER);
    }

    static String key(String eventType, String idemKey) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest((eventType + " " + idemKey).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

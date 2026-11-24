package ai.neargo.shop.platform.auth;

import ai.neargo.shop.platform.OpsApiKeyService.Access;
import ai.neargo.shop.platform.OpsApiKeyService.Metadata;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class OpsApiKeyStore {

    private static final String METADATA = "key_no,label,access_level,issued_by,created_at,expires_at,revoked_at";
    private static final RowMapper<Metadata> READ_METADATA = (rs, row) -> new Metadata(
            rs.getString("key_no"), rs.getString("label"), Access.valueOf(rs.getString("access_level")),
            rs.getString("issued_by"), rs.getLong("created_at"), rs.getLong("expires_at"),
            rs.getObject("revoked_at", Long.class));

    private final JdbcTemplate jdbc;

    public OpsApiKeyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Boolean> lockEligibleStaff(String staffNo) {
        return jdbc.query("""
                SELECT status='ACTIVE' AND must_change_password=0
                FROM sys_ops_staff WHERE staff_no=? AND deleted=0 FOR UPDATE
                """, (rs, row) -> rs.getBoolean(1), staffNo).stream().findFirst();
    }

    public void insert(String staffNo, String hash, Metadata key) {
        jdbc.update("""
                INSERT INTO sys_ops_api_key
                    (key_no,staff_no,secret_hash,label,access_level,issued_by,created_at,expires_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, key.keyNo(), staffNo, hash, key.label(), key.access().name(), key.issuedBy(),
                key.createdAt(), key.expiresAt());
    }

    public record Registration(Metadata key, String secretHash) {}

    public Optional<Registration> registration(String staffNo, String keyNo) {
        return jdbc.query("SELECT " + METADATA + """
                ,secret_hash FROM sys_ops_api_key WHERE staff_no=? AND key_no=?
                """, (rs, row) -> new Registration(READ_METADATA.mapRow(rs, row),
                rs.getString("secret_hash")), staffNo, keyNo).stream().findFirst();
    }

    public List<Metadata> list(String staffNo) {
        return jdbc.query("SELECT " + METADATA + """
                 FROM sys_ops_api_key WHERE staff_no=? ORDER BY created_at DESC,key_no
                """, READ_METADATA, staffNo);
    }

    public record Binding(String keyNo, String staffNo, Access access) {}

    public Optional<Binding> activeBinding(String hash, long now) {
        return jdbc.query("""
                SELECT k.key_no,k.staff_no,k.access_level
                FROM sys_ops_api_key k JOIN sys_ops_staff s ON s.staff_no=k.staff_no
                WHERE k.secret_hash=? AND k.revoked_at IS NULL AND k.expires_at>?
                    AND s.status='ACTIVE' AND s.deleted=0 AND s.must_change_password=0
                """, (rs, row) -> new Binding(rs.getString(1), rs.getString(2),
                Access.valueOf(rs.getString(3))), hash, now).stream().findFirst();
    }

    public boolean revoke(String staffNo, String keyNo, long now) {
        return jdbc.update("""
                UPDATE sys_ops_api_key SET revoked_at=? WHERE staff_no=? AND key_no=? AND revoked_at IS NULL
                """, now, staffNo, keyNo) == 1;
    }

    public void revokeAll(String staffNo, long now) {
        jdbc.update("UPDATE sys_ops_api_key SET revoked_at=? WHERE staff_no=? AND revoked_at IS NULL", now, staffNo);
    }
}

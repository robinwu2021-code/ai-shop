package ai.neargo.shop.platform.auth;

import ai.neargo.secret.api.CredentialVerifier;
import ai.neargo.secret.api.KeyOwner;
import ai.neargo.secret.api.SecretNamespace;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 运营接口密钥与运营账号的接缝：凭据在密钥库（{@link CredentialVerifier}，只存摘要），
 * 所有者是 {@code KeyOwner(PLATFORM, "OPS_STAFF", sys_ops_staff.id)}（TDD-基础能力库对齐 Q16）；
 * 这里只管按账号找所有者、核账号状态。{@code sys_ops_api_key} 停写停读。
 */
@Repository
public class OpsApiKeyStore {

    public static final String OWNER_TYPE = "OPS_STAFF";

    private final JdbcTemplate jdbc;
    private final CredentialVerifier credentials;

    public OpsApiKeyStore(JdbcTemplate jdbc, CredentialVerifier credentials) {
        this.jdbc = jdbc;
        this.credentials = credentials;
    }

    public static KeyOwner owner(long staffId) {
        return new KeyOwner(SecretNamespace.PLATFORM, OWNER_TYPE, staffId);
    }

    /** 锁住账号行（与停用、改密串行），返回所有者与能否持有密钥（ACTIVE 且不需改密）；账号不存在为空 */
    public Optional<Staff> lockStaff(String staffNo) {
        return jdbc.query("""
                SELECT id, status='ACTIVE' AND must_change_password=0
                FROM sys_ops_staff WHERE staff_no=? AND deleted=0 FOR UPDATE
                """, (rs, row) -> new Staff(owner(rs.getLong(1)), rs.getBoolean(2)), staffNo).stream().findFirst();
    }

    public Optional<KeyOwner> ownerOf(String staffNo) {
        return jdbc.query("SELECT id FROM sys_ops_staff WHERE staff_no=? AND deleted=0",
                (rs, row) -> owner(rs.getLong(1)), staffNo).stream().findFirst();
    }

    /** 凭据所有者对应的账号：ACTIVE、未删、不需改密才给 */
    public Optional<String> eligibleStaffNo(KeyOwner owner) {
        return jdbc.query("""
                SELECT staff_no FROM sys_ops_staff
                WHERE id=? AND status='ACTIVE' AND deleted=0 AND must_change_password=0
                """, (rs, row) -> rs.getString(1), owner.ownerId()).stream().findFirst();
    }

    /** 停用账号、改密、重置密码时调：该账号的全部有效密钥立即作废 */
    public void revokeAll(String staffNo, String reason) {
        ownerOf(staffNo).ifPresent(owner -> credentials.revokeAll(owner, "SYSTEM", reason));
    }

    public record Staff(KeyOwner owner, boolean eligible) {}
}

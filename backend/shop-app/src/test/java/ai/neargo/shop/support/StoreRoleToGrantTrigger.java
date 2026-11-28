package ai.neargo.shop.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.h2.api.Trigger;

/**
 * 测试库专用的过渡桥：把写到旧表 {@code mch_store_role} 的门店角色同步成授权表 {@code mch_grant} 的 ROLE 行。
 *
 * <p>平台基础库换表第 2 步 G 把门店角色并进了授权表，生产代码只读写 {@code mch_grant}（{@code StoreRoleGrants}）。
 * 但 20 多个测试仍按旧表直接插门店角色。这个触发器让它们照旧可用：
 * 插入（未删）= 授一条 ROLE；改成已删 / 删除 = 撤销。门店所属主体还没有集团的，按测试口径补一个集团并回填主体
 * （生产上由 {@code StoreRoleGrants} 按老板建默认集团，Q7.1）。
 *
 * <p>只挂在 H2 测试库（{@code schema-platform-test-after.sql}，由 {@code gen-platform-test-schema.py} 生成）。
 * 这些测试改成直接授 {@code mch_grant} 之后，连同旧表一起删掉。
 */
public class StoreRoleToGrantTrigger implements Trigger {

    private int accountCol = -1;
    private int storeCol = -1;
    private int roleCol = -1;
    private int deletedCol = -1;

    @Override
    public void init(Connection conn, String schema, String trigger, String table, boolean before, int type)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT LOWER(COLUMN_NAME), ORDINAL_POSITION FROM INFORMATION_SCHEMA.COLUMNS"
                + " WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    switch (rs.getString(1)) {
                        case "mch_account_no" -> accountCol = rs.getInt(2) - 1;
                        case "store_no" -> storeCol = rs.getInt(2) - 1;
                        case "role" -> roleCol = rs.getInt(2) - 1;
                        case "deleted" -> deletedCol = rs.getInt(2) - 1;
                        default -> { }
                    }
                }
            }
        }
        if (accountCol < 0 || storeCol < 0 || roleCol < 0 || deletedCol < 0) {
            throw new SQLException("mch_store_role 上找不到 mch_account_no / store_no / role / deleted：" + table);
        }
    }

    @Override
    public void fire(Connection conn, Object[] oldRow, Object[] newRow) throws SQLException {
        if (oldRow != null) {
            revoke(conn, oldRow);
        }
        if (newRow != null && !isDeleted(newRow[deletedCol])) {
            grant(conn, newRow);
        }
    }

    private static boolean isDeleted(Object value) {
        return value != null && !"0".equals(value.toString()) && !"false".equalsIgnoreCase(value.toString());
    }

    private void revoke(Connection conn, Object[] row) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE mch_grant SET status='REVOKED' WHERE actor_no=?"
                + " AND resource_type='STORE' AND resource_no=? AND grant_kind='ROLE' AND role_code=?")) {
            ps.setString(1, "MCH:" + row[accountCol]);
            ps.setObject(2, row[storeCol]);
            ps.setObject(3, row[roleCol]);
            ps.executeUpdate();
        }
    }

    private void grant(Connection conn, Object[] row) throws SQLException {
        String management = groupOfStore(conn, String.valueOf(row[storeCol]));
        if (management == null) {
            return;   // 门店不存在：旧表上这一行本来也不生效
        }
        try (PreparedStatement up = conn.prepareStatement("UPDATE mch_grant SET status='ACTIVE', valid_to=NULL, deleted=0"
                + " WHERE management_no=? AND actor_no=? AND resource_type='STORE' AND resource_no=? AND grant_kind='ROLE'"
                + " AND role_code=? AND permission_code=''")) {
            up.setString(1, management);
            up.setString(2, "MCH:" + row[accountCol]);
            up.setObject(3, row[storeCol]);
            up.setObject(4, row[roleCol]);
            if (up.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement ins = conn.prepareStatement("INSERT INTO mch_grant(tenant_no, grant_no, management_no, actor_no,"
                + " resource_type, resource_no, grant_kind, role_code, permission_code, status, valid_from,"
                + " authority_source_type, delegatable, created_at, updated_at) VALUES('MAIN', ?, ?, ?, 'STORE', ?, 'ROLE', ?, '',"
                + " 'ACTIVE', TIMESTAMP '2000-01-01 00:00:00', 'OWNER_ACCOUNT', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
            ins.setString(1, "TG" + UUID.randomUUID().toString().replace("-", "").substring(0, 28));
            ins.setString(2, management);
            ins.setString(3, "MCH:" + row[accountCol]);
            ins.setObject(4, row[storeCol]);
            ins.setObject(5, row[roleCol]);
            ins.executeUpdate();
        }
    }

    /** 门店所属主体的集团；主体还没有就补一个测试集团并回填 */
    private static String groupOfStore(Connection conn, String storeNo) throws SQLException {
        String entity;
        String management;
        try (PreparedStatement ps = conn.prepareStatement("SELECT e.entity_no, e.management_no FROM mch_store s"
                + " JOIN mch_entity e ON e.entity_no=s.entity_no WHERE s.store_no=? AND s.deleted=0")) {
            ps.setString(1, storeNo);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                entity = rs.getString(1);
                management = rs.getString(2);
            }
        }
        if (management != null && !management.isBlank()) {
            return management;
        }
        management = "TG" + entity;
        if (management.length() > 64) {
            management = management.substring(0, 64);
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO mch_group(tenant_no, management_no, name, owner_actor_no,"
                + " status, bootstrap_source_account_no, auth_revision, created_at, updated_at)"
                + " SELECT 'MAIN', ?, 'test group', 'MCH:TEST', 'ACTIVE', 'TEST', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP"
                + " WHERE NOT EXISTS (SELECT 1 FROM mch_group WHERE management_no=?)")) {
            ps.setString(1, management);
            ps.setString(2, management);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("UPDATE mch_entity SET management_no=? WHERE entity_no=?")) {
            ps.setString(1, management);
            ps.setString(2, entity);
            ps.executeUpdate();
        }
        return management;
    }
}

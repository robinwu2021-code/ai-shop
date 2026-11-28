package ai.neargo.shop.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import org.h2.api.Trigger;

/**
 * 测试库专用的过渡桥：把写到 {@code mch_role.perms}（旧的 JSON 列）的权限码同步成 {@code mch_role_point} 的行。
 *
 * <p>平台基础库换表第 2 步 B 把角色权限码拆成了一码一行，生产代码只读写 {@code mch_role_point}。
 * 但测试库的旧种子（历史迁移里预置角色的 INSERT 与几十条追加码的 UPDATE）和 19 个测试仍按 {@code perms} 写角色。
 * 这个触发器让它们照旧可用：{@code perms} 有值时按它整体替换该角色的权限码（与数据搬迁把 {@code perms} 拆成行同一口径），
 * 删角色时一并删码；{@code perms} 为空（生产代码建、改角色时不写这一列）则不动。
 *
 * <p>只挂在 H2 测试库（{@code schema-platform-test.sql} 由 {@code gen-platform-test-schema.py} 生成），
 * {@code mch_role.perms} 在那里是过渡列。旧测试改成直接写 {@code mch_role_point} 之后，连同过渡列一起删掉。
 */
public class RolePermsToPointsTrigger implements Trigger {

    private int entityCol = -1;
    private int roleCol = -1;
    private int permsCol = -1;

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
                        case "entity_no" -> entityCol = rs.getInt(2) - 1;
                        case "role_code" -> roleCol = rs.getInt(2) - 1;
                        case "perms" -> permsCol = rs.getInt(2) - 1;
                        default -> { }
                    }
                }
            }
        }
        if (entityCol < 0 || roleCol < 0 || permsCol < 0) {
            throw new SQLException("mch_role 上找不到 entity_no / role_code / perms：" + table);
        }
    }

    @Override
    public void fire(Connection conn, Object[] oldRow, Object[] newRow) throws SQLException {
        if (newRow == null) {                                   // 删角色：码一起删
            delete(conn, oldRow[entityCol], oldRow[roleCol]);
            return;
        }
        Object perms = newRow[permsCol];
        if (perms == null) {
            return;                                             // 生产代码的写入：码由它自己维护
        }
        if (oldRow != null && (!oldRow[entityCol].equals(newRow[entityCol]) || !oldRow[roleCol].equals(newRow[roleCol]))) {
            delete(conn, oldRow[entityCol], oldRow[roleCol]);
        }
        delete(conn, newRow[entityCol], newRow[roleCol]);
        String[] codes = perms.toString().replaceAll("[\\[\\]\"]", "").split(",");
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO mch_role_point(entity_no, role_code, perm_code,"
                + " granted_at, created_at, updated_at) VALUES(?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")) {
            for (String code : Arrays.stream(codes).map(String::trim).filter(c -> !c.isEmpty()).distinct().toList()) {
                ps.setObject(1, newRow[entityCol]);
                ps.setObject(2, newRow[roleCol]);
                ps.setString(3, code);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void delete(Connection conn, Object entity, Object role) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM mch_role_point WHERE entity_no=? AND role_code=?")) {
            ps.setObject(1, entity);
            ps.setObject(2, role);
            ps.executeUpdate();
        }
    }
}

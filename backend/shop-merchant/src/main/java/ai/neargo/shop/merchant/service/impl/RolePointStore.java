package ai.neargo.shop.merchant.service.impl;

import ai.neargo.shop.common.config.PlatformJdbcConfiguration;
import ai.neargo.shop.merchant.entity.MchRole;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * B 端角色的权限码：一个角色一个码一行（{@code mch_role_point}）。原先是角色行上的一段 JSON（{@code mch_role.perms}），
 * 新库拆成了行（TDD-平台基础库换表 第 2 步 B）。判定规则不变：角色认码，预置角色挂在 {@link MchRole#BUILTIN_ENTITY} 下，
 * 同码的自定义角色覆盖预置。写入随调用方的平台事务（同一个平台句柄）。
 */
@Component
public class RolePointStore {

    private final NamedParameterJdbcTemplate jdbc;

    public RolePointStore(@Qualifier(PlatformJdbcConfiguration.PLATFORM_JDBC_TEMPLATE) JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    /** 一个角色的权限码 */
    public Set<String> of(String entityNo, String roleCode) {
        return of(List.of(new Key(entityNo, roleCode))).getOrDefault(new Key(entityNo, roleCode), Set.of());
    }

    /** 一批角色各自的权限码；没有码的角色不出现在结果里 */
    public Map<Key, Set<String>> ofRoles(Collection<MchRole> roles) {
        return of(roles.stream().map(r -> new Key(r.getEntityNo(), r.getRoleCode())).toList());
    }

    public Map<Key, Set<String>> of(Collection<Key> keys) {
        Map<Key, Set<String>> out = new HashMap<>();
        if (keys.isEmpty()) {
            return out;
        }
        Set<String> entities = new LinkedHashSet<>();
        Set<String> codes = new LinkedHashSet<>();
        keys.forEach(k -> {
            entities.add(k.entityNo());
            codes.add(k.roleCode());
        });
        jdbc.query("SELECT entity_no, role_code, perm_code FROM mch_role_point"
                        + " WHERE entity_no IN (:entities) AND role_code IN (:codes) ORDER BY id",
                new MapSqlParameterSource("entities", entities).addValue("codes", codes), rs -> {
                    Key k = new Key(rs.getString("entity_no"), rs.getString("role_code"));
                    if (keys.contains(k)) {
                        out.computeIfAbsent(k, x -> new LinkedHashSet<>()).add(rs.getString("perm_code"));
                    }
                });
        return out;
    }

    /** 整体替换一个角色的权限码（建角色、改角色） */
    public void replace(String entityNo, String roleCode, Set<String> perms) {
        delete(entityNo, roleCode);
        var params = perms.stream().map(p -> new MapSqlParameterSource("entity", entityNo)
                .addValue("role", roleCode).addValue("perm", p)).toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("INSERT INTO mch_role_point(entity_no, role_code, perm_code, granted_at, created_at, updated_at)"
                + " VALUES(:entity, :role, :perm, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", params);
    }

    public void delete(String entityNo, String roleCode) {
        jdbc.update("DELETE FROM mch_role_point WHERE entity_no=:entity AND role_code=:role",
                new MapSqlParameterSource("entity", entityNo).addValue("role", roleCode));
    }

    public record Key(String entityNo, String roleCode) {
    }
}

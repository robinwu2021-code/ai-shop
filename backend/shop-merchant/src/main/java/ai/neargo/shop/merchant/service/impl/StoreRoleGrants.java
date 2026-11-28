package ai.neargo.shop.merchant.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.config.PlatformJdbcConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 员工在门店上的角色：授权表 {@code mch_grant} 里 {@code grant_kind=ROLE} 的行（原 {@code mch_store_role}，
 * TDD-平台基础库换表 第 2 步 G，Q7）。
 *
 * <p>一行 = 一个 B 端账号（{@code actor_no = MCH:账号号}）在一家门店（{@code STORE/门店号}）上的一个角色，
 * 挂在门店所属主体的集团下，不可转授（7.4），来源 {@code OWNER_ACCOUNT}。
 *
 * <p><b>生效</b>（7.3）：状态 ACTIVE、在有效期内、集团 ACTIVE，且门店现在所属的主体仍在这个集团里。
 * 不复核当初分配角色的那个人。读方法只返回生效的行。
 *
 * <p>主体还没有集团的（7.1），授角色时按老板建默认集团：同一老板（有关联 C 端用户的按 {@code USER:} 用户号，
 * 否则按 {@code MCH:} 账号号）名下的主体共用一个。
 */
@Component
public class StoreRoleGrants {

    public static final String KIND_ROLE = "ROLE";
    public static final String RESOURCE_STORE = "STORE";
    static final String ACTIVE = "ACTIVE";
    static final String REVOKED = "REVOKED";
    static final String SOURCE_OWNER_ACCOUNT = "OWNER_ACCOUNT";

    /** 生效条件：拼在 {@code mch_grant g} 上 */
    static final String LIVE = " g.grant_kind='ROLE' AND g.resource_type='STORE' AND g.status='ACTIVE' AND g.deleted=0"
            + " AND g.valid_from<=:now AND (g.valid_to IS NULL OR g.valid_to>:now)"
            + " AND EXISTS (SELECT 1 FROM mch_group m WHERE m.tenant_no=g.tenant_no AND m.management_no=g.management_no"
            + " AND m.status='ACTIVE' AND m.deleted=0)"
            + " AND EXISTS (SELECT 1 FROM mch_store s JOIN mch_entity e ON e.tenant_no=s.tenant_no AND e.entity_no=s.entity_no"
            + " AND e.deleted=0 WHERE s.tenant_no=g.tenant_no AND s.store_no=g.resource_no AND s.deleted=0"
            + " AND e.management_no=g.management_no)";

    private final NamedParameterJdbcTemplate jdbc;

    public StoreRoleGrants(@Qualifier(PlatformJdbcConfiguration.PLATFORM_JDBC_TEMPLATE) JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    /** 账号号 → 生效角色授权里的 actor_no */
    public static String actorOf(String mchAccountNo) {
        return "MCH:" + mchAccountNo;
    }

    /** 这些账号的生效门店角色 */
    public List<StoreRole> ofAccounts(Collection<String> accountNos) {
        if (accountNos == null || accountNos.isEmpty()) {
            return List.of();
        }
        return query(" AND g.actor_no IN (:actors)", new MapSqlParameterSource("actors",
                accountNos.stream().map(StoreRoleGrants::actorOf).toList()));
    }

    /** 这些门店上的生效角色 */
    public List<StoreRole> ofStores(Collection<String> storeNos) {
        if (storeNos == null || storeNos.isEmpty()) {
            return List.of();
        }
        return query(" AND g.resource_no IN (:stores)", new MapSqlParameterSource("stores", storeNos));
    }

    private List<StoreRole> query(String filter, MapSqlParameterSource p) {
        return jdbc.query("SELECT g.actor_no, g.resource_no, g.role_code FROM mch_grant g WHERE g.tenant_no='MAIN' AND"
                        + LIVE + filter + " ORDER BY g.id", p.addValue("now", Timestamp.from(Instant.now())),
                (rs, i) -> new StoreRole(rs.getString("actor_no").substring(4), rs.getString("resource_no"),
                        rs.getString("role_code")));
    }

    /** 这个账号在这家店上有没有这个角色（生效的） */
    public boolean has(String accountNo, String storeNo, String role) {
        return !query(" AND g.actor_no=:actor AND g.resource_no=:store AND g.role_code=:role",
                new MapSqlParameterSource("actor", actorOf(accountNo)).addValue("store", storeNo).addValue("role", role))
                .isEmpty();
    }

    /**
     * 授角色。已有同一行（任何状态）就重新生效，否则新插；随调用方的平台事务。
     *
     * @param actor 操作人（带前缀）
     */
    public void grant(String storeNo, String accountNo, String role, String actor) {
        String management = ensureGroupOfStore(storeNo);
        var p = new MapSqlParameterSource("management", management).addValue("actor", actorOf(accountNo))
                .addValue("store", storeNo).addValue("role", role).addValue("by", actor)
                .addValue("now", Timestamp.from(Instant.now()));
        int changed = jdbc.update("UPDATE mch_grant SET status='ACTIVE', valid_from=:now, valid_to=NULL, deleted=0,"
                + " updated_at=:now, updated_by=:by, version=version+1 WHERE tenant_no='MAIN' AND management_no=:management"
                + " AND actor_no=:actor AND resource_type='STORE' AND resource_no=:store AND grant_kind='ROLE'"
                + " AND role_code=:role AND permission_code=''", p);
        if (changed > 0) {
            return;
        }
        jdbc.update("INSERT INTO mch_grant(tenant_no, grant_no, management_no, actor_no, resource_type, resource_no,"
                + " grant_kind, role_code, permission_code, status, valid_from, authority_source_type, authority_source_no,"
                + " delegatable, created_at, created_by, updated_at, updated_by) VALUES('MAIN', :no, :management, :actor,"
                + " 'STORE', :store, 'ROLE', :role, '', 'ACTIVE', :now, 'OWNER_ACCOUNT', :source, 0, :now, :by, :now, :by)",
                p.addValue("no", BizKey.next(BizKey.MANAGEMENT_GRANT)).addValue("source", ownerAccountOfStore(storeNo)));
    }

    /** 撤角色（生效与否都撤）；返回撤掉的行数 */
    public int revoke(String storeNo, String accountNo, String role, String actor) {
        return jdbc.update("UPDATE mch_grant SET status='REVOKED', updated_at=:now, updated_by=:by, version=version+1"
                        + " WHERE tenant_no='MAIN' AND actor_no=:actor AND resource_type='STORE' AND resource_no=:store"
                        + " AND grant_kind='ROLE' AND role_code=:role AND status='ACTIVE'",
                new MapSqlParameterSource("actor", actorOf(accountNo)).addValue("store", storeNo).addValue("role", role)
                        .addValue("by", actor).addValue("now", Timestamp.from(Instant.now())));
    }

    /** 门店上非某主体老板的员工角色全部撤掉（门店改认领到别的主体时用） */
    public int revokeStaffOfStore(String storeNo, String keepOwnerOfEntity, String actor) {
        return jdbc.update("UPDATE mch_grant SET status='REVOKED', updated_at=:now, updated_by=:by, version=version+1"
                        + " WHERE tenant_no='MAIN' AND resource_type='STORE' AND resource_no=:store AND grant_kind='ROLE'"
                        + " AND status='ACTIVE' AND NOT EXISTS (SELECT 1 FROM mch_account a WHERE a.tenant_no='MAIN'"
                        + " AND CONCAT('MCH:', a.mch_account_no)=mch_grant.actor_no AND a.entity_no=:entity"
                        + " AND a.is_owner=1 AND a.deleted=0)",
                new MapSqlParameterSource("store", storeNo).addValue("entity", keepOwnerOfEntity)
                        .addValue("by", actor).addValue("now", Timestamp.from(Instant.now())));
    }

    /** 门店所属主体的集团号；主体还没有集团就按老板建默认集团并回填（7.1） */
    String ensureGroupOfStore(String storeNo) {
        var rows = jdbc.queryForList("SELECT e.entity_no, e.management_no FROM mch_store s JOIN mch_entity e"
                + " ON e.tenant_no=s.tenant_no AND e.entity_no=s.entity_no AND e.deleted=0"
                + " WHERE s.tenant_no='MAIN' AND s.store_no=:store AND s.deleted=0 FOR UPDATE",
                new MapSqlParameterSource("store", storeNo));
        if (rows.isEmpty()) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        String entity = (String) rows.getFirst().get("entity_no");
        String management = (String) rows.getFirst().get("management_no");
        if (management != null && !management.isBlank()) {
            return management;
        }
        Map<String, Object> owner = ownerOf(entity);
        if (owner == null) {
            throw BizException.of(ErrorCode.CONFLICT);   // 主体还没有老板账号：不能凭空建集团
        }
        String ownerActor = owner.get("user_no") != null ? "USER:" + owner.get("user_no") : actorOf((String) owner.get("mch_account_no"));
        var existing = jdbc.queryForList("SELECT management_no FROM mch_group WHERE tenant_no='MAIN' AND owner_actor_no=:owner"
                + " AND status='ACTIVE' AND deleted=0 ORDER BY id LIMIT 1", new MapSqlParameterSource("owner", ownerActor));
        if (!existing.isEmpty()) {
            management = (String) existing.getFirst().get("management_no");
        } else {
            management = BizKey.next(BizKey.MANAGEMENT_SPACE);
            Timestamp now = Timestamp.from(Instant.now());
            jdbc.update("INSERT INTO mch_group(tenant_no, management_no, name, owner_actor_no, status, bootstrap_source_account_no,"
                    + " auth_revision, created_at, created_by, updated_at, updated_by) VALUES('MAIN', :no, :name, :owner, 'ACTIVE',"
                    + " :account, 0, :now, 'SYSTEM', :now, 'SYSTEM')", new MapSqlParameterSource("no", management)
                    .addValue("name", nameOf(entity)).addValue("owner", ownerActor)
                    .addValue("account", owner.get("mch_account_no")).addValue("now", now));
        }
        jdbc.update("UPDATE mch_entity SET management_no=:m, version=version+1 WHERE tenant_no='MAIN' AND entity_no=:e"
                + " AND management_no IS NULL", new MapSqlParameterSource("m", management).addValue("e", entity));
        return management;
    }

    private Map<String, Object> ownerOf(String entityNo) {
        var rows = jdbc.queryForList("SELECT mch_account_no, user_no FROM mch_account WHERE tenant_no='MAIN' AND entity_no=:e"
                + " AND is_owner=1 AND status='ACTIVE' AND deleted=0 ORDER BY id LIMIT 1", new MapSqlParameterSource("e", entityNo));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private String ownerAccountOfStore(String storeNo) {
        var rows = jdbc.queryForList("SELECT a.mch_account_no FROM mch_store s JOIN mch_account a ON a.tenant_no=s.tenant_no"
                + " AND a.entity_no=s.entity_no AND a.is_owner=1 AND a.deleted=0 WHERE s.tenant_no='MAIN' AND s.store_no=:store"
                + " ORDER BY a.id LIMIT 1", new MapSqlParameterSource("store", storeNo));
        return rows.isEmpty() ? null : (String) rows.getFirst().get("mch_account_no");
    }

    private String nameOf(String entityNo) {
        var rows = jdbc.queryForList("SELECT name FROM mch_entity WHERE tenant_no='MAIN' AND entity_no=:e",
                new MapSqlParameterSource("e", entityNo));
        Object name = rows.isEmpty() ? null : rows.getFirst().get("name");
        return name == null ? entityNo : name.toString();
    }

    /** 一条生效的门店角色 */
    public record StoreRole(String accountNo, String storeNo, String role) {
    }
}

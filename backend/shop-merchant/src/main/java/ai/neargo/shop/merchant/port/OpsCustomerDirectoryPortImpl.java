package ai.neargo.shop.merchant.port;

import ai.neargo.shop.common.config.ModuleDataSources;
import ai.neargo.shop.common.config.PlatformJdbcConfiguration;
import ai.neargo.shop.spi.user.OpsCustomerDirectoryPort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运营端顾客浏览的平台事实：账号、人、主体、门店、品牌，经平台句柄读。SQL 从门户的顾客浏览原样搬来，
 * 只拆掉了与会员表的连接（会员一侧由门户查）。
 */
@Component
@Profile("ops")
@Transactional(transactionManager = ModuleDataSources.PLATFORM_TX_MANAGER, readOnly = true,
        isolation = Isolation.REPEATABLE_READ)
public class OpsCustomerDirectoryPortImpl implements OpsCustomerDirectoryPort {

    private final NamedParameterJdbcTemplate jdbc;

    public OpsCustomerDirectoryPortImpl(
            @Qualifier(PlatformJdbcConfiguration.PLATFORM_JDBC_TEMPLATE) JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    private static MapSqlParameterSource params(Scope s) {
        return new MapSqlParameterSource("tenant", s.tenantNo())
                .addValue("entities", s.entities().isEmpty() ? Set.of("__NO_SCOPE__") : s.entities());
    }

    private static String scope(Scope s, String entity) {
        return s.all() ? "1=1" : s.entities().isEmpty() ? "1=0" : entity + " IN (:entities)";
    }

    private static String like(String keyword) {
        return "%" + Objects.toString(keyword, "") + "%";
    }

    private static String accountWhere(Scope s) {
        // usr_account.entity_no is a favourite store, never an ownership or visibility anchor.
        return "u.tenant_no=:tenant AND u.deleted=0 AND " + (s.all() ? "1=1"
                : "EXISTS (SELECT 1 FROM mch_account a WHERE a.tenant_no=u.tenant_no AND a.user_no=u.user_no AND a.deleted=0 AND a.status='ACTIVE' AND "
                        + scope(s, "a.entity_no") + ")");
    }

    private static String registrationStoreWhere(Scope s) {
        return "a.tenant_no=:tenant AND a.user_no=u.user_no AND a.deleted=0 AND a.status='ACTIVE' AND "
                + scope(s, "a.entity_no");
    }

    private static String brandJoins() {
        return " LEFT JOIN mch_brand_store_binding bb ON bb.tenant_no=st.tenant_no AND bb.store_no=st.store_no AND bb.deleted=0 AND bb.status='ACTIVE' AND bb.effective_from<=UTC_TIMESTAMP(3) AND (bb.effective_to IS NULL OR bb.effective_to>UTC_TIMESTAMP(3)) LEFT JOIN mch_brand b ON b.tenant_no=bb.tenant_no AND b.brand_no=bb.brand_no AND b.deleted=0 ";
    }

    private static StoreLink store(ResultSet r, int n) throws SQLException {
        return new StoreLink(r.getString("store_no"), r.getString("name"), r.getString("entity_no"),
                r.getString("brand_no"), r.getString("brand_name"));
    }

    @Override
    public Optional<OperatingEntityFact> operatingEntity(Scope s, String id) {
        var p = params(s).addValue("id", id);
        String visible = s.all() ? "1=1"
                : "(oe.legal_subject_no IN (:entities) OR (oe.legal_subject_no IS NULL AND EXISTS (SELECT 1 FROM mch_account a WHERE a.tenant_no=oe.tenant_no COLLATE utf8mb4_unicode_520_ci AND a.user_no=oe.owner_user_no COLLATE utf8mb4_unicode_520_ci AND a.deleted=0 AND a.status='ACTIVE' AND a.is_owner=1 AND "
                        + scope(s, "a.entity_no") + ")))";
        return jdbc.query("SELECT oe.operating_entity_no,oe.name,oe.market,oe.status,oe.version,oe.legal_subject_no,oe.owner_user_no,("
                + visible
                + ") visible FROM mch_operating_entity oe JOIN usr_account u ON u.tenant_no=oe.tenant_no COLLATE utf8mb4_unicode_520_ci AND u.user_no=oe.owner_user_no COLLATE utf8mb4_unicode_520_ci AND u.deleted=0 AND u.status='NORMAL' WHERE oe.tenant_no=:tenant AND oe.operating_entity_no=:id AND oe.deleted=0 AND oe.status='ENABLED'",
                p, (r, n) -> new OperatingEntityFact(r.getString("operating_entity_no"), r.getString("name"),
                        r.getString("market"), r.getString("status"), r.getLong("version"),
                        r.getString("legal_subject_no"), r.getString("owner_user_no"), r.getBoolean("visible")))
                .stream().findFirst();
    }

    @Override
    public Registrations registrations(Scope s, String keyword, long page, long size, LocalDate dailyFrom) {
        var p = params(s).addValue("keyword", like(keyword)).addValue("limit", size)
                .addValue("offset", (page - 1) * size);
        String where = accountWhere(s) + " AND (u.user_no LIKE :keyword OR u.nickname LIKE :keyword)";
        String associations = registrationStoreWhere(s);
        String storeCount = "(SELECT COUNT(DISTINCT st.store_no) FROM mch_account a JOIN mch_store st ON st.tenant_no=a.tenant_no AND st.entity_no=a.entity_no AND st.deleted=0 WHERE "
                + associations + ")";
        var records = jdbc.query("SELECT u.user_no,u.nickname,u.status,u.market,u.created_at,"
                + "(SELECT COUNT(DISTINCT a.entity_no) FROM mch_account a WHERE " + associations + ") entity_count,"
                + storeCount + " store_count FROM usr_account u WHERE " + where
                + " ORDER BY u.created_at DESC,u.user_no DESC LIMIT :limit OFFSET :offset", p,
                (r, n) -> new Registration(r.getString("user_no"), r.getString("nickname"), r.getString("status"),
                        r.getString("market"), r.getTimestamp("created_at").toInstant().toEpochMilli(),
                        r.getLong("entity_count"), r.getLong("store_count")));
        long total = count("SELECT COUNT(*) FROM usr_account u WHERE " + where, p);
        long without = count("SELECT COUNT(*) FROM usr_account u WHERE " + where + " AND " + storeCount + "=0", p);
        p.addValue("from", dailyFrom.atStartOfDay());
        var daily = new HashMap<String, Long>();
        jdbc.query("SELECT DATE(u.created_at) day,COUNT(*) total FROM usr_account u WHERE " + where
                + " AND u.created_at>=:from GROUP BY DATE(u.created_at)", p,
                r -> {
                    daily.put(r.getString("day"), r.getLong("total"));
                });
        return new Registrations(records, total, without, daily);
    }

    @Override
    public List<StoreLink> registrationStores(Scope s, String userNo) {
        var p = params(s).addValue("user", userNo);
        return jdbc.query("SELECT DISTINCT st.store_no,st.name,st.entity_no,b.brand_no,b.name brand_name FROM mch_account a JOIN usr_account u ON u.tenant_no=a.tenant_no AND u.user_no=a.user_no AND u.deleted=0 JOIN mch_store st ON st.tenant_no=a.tenant_no AND st.entity_no=a.entity_no AND st.deleted=0 "
                + brandJoins() + " WHERE a.tenant_no=:tenant AND a.user_no=:user AND a.deleted=0 AND a.status='ACTIVE' AND "
                + scope(s, "a.entity_no") + " ORDER BY st.store_no", p, OpsCustomerDirectoryPortImpl::store);
    }

    @Override
    public Brands brands(Scope s, String keyword, long page, long size) {
        var p = params(s).addValue("keyword", like(keyword)).addValue("limit", size)
                .addValue("offset", (page - 1) * size);
        String binding = "bb.tenant_no=b.tenant_no AND bb.brand_no=b.brand_no AND bb.deleted=0 AND bb.status='ACTIVE' AND bb.effective_from<=UTC_TIMESTAMP(3) AND (bb.effective_to IS NULL OR bb.effective_to>UTC_TIMESTAMP(3)) AND st.deleted=0 AND "
                + scope(s, "st.entity_no");
        String where = "b.tenant_no=:tenant AND b.deleted=0 AND (b.name LIKE :keyword OR b.brand_no LIKE :keyword)"
                + (s.all() ? ""
                        : " AND EXISTS (SELECT 1 FROM mch_brand_store_binding bb JOIN mch_store st ON st.tenant_no=bb.tenant_no AND st.store_no=bb.store_no WHERE "
                                + binding + ")");
        long total = count("SELECT COUNT(*) FROM mch_brand b WHERE " + where, p);
        var rows = jdbc.query("SELECT b.brand_no,b.name,b.status,b.management_no,ms.name management_name FROM mch_brand b LEFT JOIN mch_management_space ms ON ms.tenant_no=b.tenant_no AND ms.management_no=b.management_no AND ms.deleted=0 WHERE "
                + where + " ORDER BY b.brand_no LIMIT :limit OFFSET :offset", p,
                (r, n) -> new Brand(r.getString("brand_no"), r.getString("name"), r.getString("status"),
                        r.getString("management_no"), r.getString("management_name"), List.of()));
        var result = new ArrayList<Brand>();
        for (var b : rows) {
            p.addValue("brand", b.brandNo());
            var stores = jdbc.query("SELECT st.store_no,st.name,st.entity_no,b.brand_no,b.name brand_name FROM mch_brand b JOIN mch_brand_store_binding bb ON bb.tenant_no=b.tenant_no AND bb.brand_no=b.brand_no JOIN mch_store st ON st.tenant_no=bb.tenant_no AND st.store_no=bb.store_no WHERE b.tenant_no=:tenant AND b.brand_no=:brand AND "
                    + binding + " ORDER BY st.store_no", p, OpsCustomerDirectoryPortImpl::store);
            result.add(new Brand(b.brandNo(), b.name(), b.status(), b.managementNo(), b.managementName(), stores));
        }
        return new Brands(result, total);
    }

    @Override
    public Set<String> storesMatching(Scope s, String storeNo, String brandNo) {
        var p = params(s);
        String filter = "";
        if (storeNo != null) {
            p.addValue("store", storeNo);
            filter += " AND st.store_no=:store";
        }
        if (brandNo != null) {
            p.addValue("brand", brandNo);
            filter += " AND b.brand_no=:brand";
        }
        return new HashSet<>(jdbc.queryForList("SELECT DISTINCT st.store_no FROM mch_store st" + brandJoins()
                + " WHERE st.tenant_no=:tenant AND st.deleted=0 AND " + scope(s, "st.entity_no") + filter, p,
                String.class));
    }

    @Override
    public List<StoreLink> storeLinks(Scope s, Collection<String> storeNos) {
        if (storeNos.isEmpty()) {
            return List.of();
        }
        var p = params(s).addValue("stores", storeNos);
        return jdbc.query("SELECT DISTINCT st.store_no,st.name,st.entity_no,b.brand_no,b.name brand_name FROM mch_store st"
                + brandJoins() + " WHERE st.tenant_no=:tenant AND st.store_no IN (:stores) AND st.deleted=0 AND "
                + scope(s, "st.entity_no") + " ORDER BY st.store_no", p, OpsCustomerDirectoryPortImpl::store);
    }

    @Override
    public Map<String, String> storeNames(Scope s, Collection<String> storeNos) {
        if (storeNos.isEmpty()) {
            return Map.of();
        }
        var p = params(s).addValue("stores", storeNos);
        var names = new HashMap<String, String>();
        jdbc.query("SELECT st.store_no,st.name FROM mch_store st WHERE st.tenant_no=:tenant AND st.store_no IN (:stores) AND "
                + scope(s, "st.entity_no"), p, r -> {
                    names.put(r.getString("store_no"), r.getString("name"));
                });
        return names;
    }

    @Override
    public Map<String, String> entityNames(String tenantNo, Collection<String> entityNos) {
        if (entityNos.isEmpty()) {
            return Map.of();
        }
        var names = new HashMap<String, String>();
        jdbc.query("SELECT e.entity_no,e.name FROM mch_entity e WHERE e.tenant_no=:tenant AND e.entity_no IN (:entities)",
                new MapSqlParameterSource("tenant", tenantNo).addValue("entities", entityNos), r -> {
                    names.put(r.getString("entity_no"), r.getString("name"));
                });
        return names;
    }

    @Override
    public Set<String> personsMatching(String tenantNo, String keyword) {
        return new HashSet<>(jdbc.queryForList("SELECT p.person_no FROM usr_person p WHERE p.tenant_no=:tenant AND p.deleted=0 AND (p.person_no LIKE :keyword OR p.phone_tail LIKE :keyword)",
                new MapSqlParameterSource("tenant", tenantNo).addValue("keyword", like(keyword)), String.class));
    }

    @Override
    public Set<String> deletedPersons(String tenantNo) {
        return new HashSet<>(jdbc.queryForList("SELECT p.person_no FROM usr_person p WHERE p.tenant_no=:tenant AND p.deleted<>0",
                new MapSqlParameterSource("tenant", tenantNo), String.class));
    }

    @Override
    public Map<String, PersonFact> persons(String tenantNo, Collection<String> personNos) {
        if (personNos.isEmpty()) {
            return Map.of();
        }
        var persons = new LinkedHashMap<String, PersonFact>();
        jdbc.query("SELECT p.person_no,p.user_no,p.phone_tail,p.status FROM usr_person p WHERE p.tenant_no=:tenant AND p.person_no IN (:persons) AND p.deleted=0",
                new MapSqlParameterSource("tenant", tenantNo).addValue("persons", personNos), r -> {
                    persons.put(r.getString("person_no"), new PersonFact(r.getString("person_no"),
                            r.getString("user_no"), r.getString("phone_tail"), r.getString("status")));
                });
        return persons;
    }

    private long count(String sql, MapSqlParameterSource p) {
        return jdbc.queryForObject(sql, p, Long.class);
    }
}

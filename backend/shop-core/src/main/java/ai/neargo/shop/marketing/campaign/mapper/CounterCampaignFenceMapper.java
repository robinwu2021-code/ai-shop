package ai.neargo.shop.marketing.campaign.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Explicit primary-key coordination SQL has no ORM entity and must never acquire selected owner-row locks. */
@Mapper
public interface CounterCampaignFenceMapper {
    /** A current locking read, including a second read after lazy insertion, cannot reuse a session cache entry. */
    @Select("""
            SELECT revision FROM mkt_counter_campaign_fence
            WHERE tenant_no = #{tenant} AND scope_kind = #{kind} AND scope_no = #{no} FOR SHARE
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Long lockShared(@Param("tenant") String tenant, @Param("kind") String kind, @Param("no") String no);

    /** The durable unique key serializes concurrent first use; an absent-row query alone is not protection. */
    @Insert("""
            INSERT IGNORE INTO mkt_counter_campaign_fence (tenant_no, scope_kind, scope_no, revision)
            VALUES (#{tenant}, #{kind}, #{no}, 0)
            """)
    int ensure(@Param("tenant") String tenant, @Param("kind") String kind, @Param("no") String no);

    /** Exact physical columns, including defaults and collation, are part of this capability's contract. */
    @Select("""
            SELECT COLUMN_NAME AS name, COLUMN_TYPE AS type, IS_NULLABLE AS nullable,
                COLUMN_DEFAULT AS default_value, CHARACTER_SET_NAME AS charset,
                COLLATION_NAME AS collation, EXTRA AS extra
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mkt_counter_campaign_fence'
            ORDER BY ORDINAL_POSITION
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Map<String, Object>> columns();

    /** Fence defaults must inherit the database's actual comparison, validated against effective owner columns. */
    @Select("""
            SELECT t.ENGINE AS engine, t.TABLE_COLLATION AS collation, c.CHARACTER_SET_NAME AS charset,
                s.DEFAULT_CHARACTER_SET_NAME AS database_charset, s.DEFAULT_COLLATION_NAME AS database_collation
            FROM information_schema.TABLES t
            JOIN information_schema.COLLATION_CHARACTER_SET_APPLICABILITY c ON c.COLLATION_NAME = t.TABLE_COLLATION
            JOIN information_schema.SCHEMATA s ON s.SCHEMA_NAME = t.TABLE_SCHEMA
            WHERE t.TABLE_SCHEMA = DATABASE() AND t.TABLE_NAME = 'mkt_counter_campaign_fence'
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Map<String, Object>> table();

    /** Exactly ten effective owner columns prove equality; table/database defaults cannot prove column overrides. */
    @Select("""
            SELECT TABLE_NAME AS owner_table, COLUMN_NAME AS owner_column,
                CHARACTER_SET_NAME AS charset, COLLATION_NAME AS collation
            FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND (
                (COLUMN_NAME = 'tenant_no' AND TABLE_NAME IN ('mkt_campaign', 'pmt_activity', 'pmt_activity_version',
                    'pmt_activity_goods', 'pmt_activity_rule', 'pmt_enrollment', 'pmt_enrollment_goods'))
                OR (COLUMN_NAME = 'entity_no' AND TABLE_NAME IN ('mkt_campaign', 'pmt_activity', 'pmt_enrollment')))
            ORDER BY TABLE_NAME, COLUMN_NAME
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Map<String, Object>> ownerIdentityBindings();

    /** The ordered composite primary key is the locking boundary; no partial key is sufficient. */
    @Select("""
            SELECT COLUMN_NAME FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mkt_counter_campaign_fence'
                AND INDEX_NAME = 'PRIMARY' ORDER BY SEQ_IN_INDEX
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<String> primaryKey();

    /** Read both names and expressions so a disabled, missing, or changed guard cannot advertise readiness. */
    @Select("""
            SELECT c.CONSTRAINT_NAME AS name, c.CHECK_CLAUSE AS clause
            FROM information_schema.CHECK_CONSTRAINTS c
            JOIN information_schema.TABLE_CONSTRAINTS t ON t.CONSTRAINT_SCHEMA = c.CONSTRAINT_SCHEMA
                AND t.CONSTRAINT_NAME = c.CONSTRAINT_NAME
            WHERE t.TABLE_SCHEMA = DATABASE() AND t.TABLE_NAME = 'mkt_counter_campaign_fence'
                AND t.CONSTRAINT_TYPE = 'CHECK'
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Map<String, Object>> checks();

    /** MySQL exposes disabled CHECKs explicitly; MariaDB enforces its CHECK definitions without this column. */
    @Select("""
            SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'information_schema'
                AND TABLE_NAME = 'TABLE_CONSTRAINTS' AND COLUMN_NAME = 'ENFORCED'
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    long hasCheckEnforcementColumn();

    /** Where the server supports NOT ENFORCED, the expression alone is insufficient capability evidence. */
    @Select("""
            SELECT ENFORCED FROM information_schema.TABLE_CONSTRAINTS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mkt_counter_campaign_fence'
                AND CONSTRAINT_TYPE = 'CHECK'
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<String> checkEnforcement();

    /** Every event on the seven predicate tables is inspected; no prefix scan or guessed owner is used. */
    @Select("""
            SELECT TRIGGER_NAME AS name, EVENT_MANIPULATION AS event, ACTION_TIMING AS timing,
                EVENT_OBJECT_TABLE AS owner_table, ACTION_STATEMENT AS body
            FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = DATABASE()
                AND EVENT_OBJECT_TABLE IN ('mkt_campaign', 'pmt_activity', 'pmt_activity_version',
                    'pmt_activity_goods', 'pmt_activity_rule', 'pmt_enrollment', 'pmt_enrollment_goods')
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Map<String, Object>> triggers();
}

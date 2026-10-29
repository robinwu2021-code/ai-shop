package ai.neargo.shop.commerce.mapper;

import ai.neargo.shop.commerce.dto.ChannelStatisticsContracts.Provider;
import ai.neargo.shop.commerce.dto.ChannelStatisticsContracts.StatusCount;
import ai.neargo.shop.commerce.entity.CimSyncRun;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Bound predicates keep aggregates in SQL; no source order collection is loaded into application
 * memory.
 */
public final class CommerceStatisticsMappers {
    private CommerceStatisticsMappers() {}

    public record Window(
            String entityNo,
            String storeNo,
            LocalDateTime from,
            LocalDateTime to,
            String providerNo,
            String productCode,
            String type,
            String connectionNo) {}

    public record HealthRow(
            String connectionNo,
            String profileNo,
            String connectionStatus,
            String accountStatus,
            String profileStatus,
            String sourceOwner,
            String authorizationStatus,
            LocalDateTime authorizationExpiresAt,
            String catalogConsistency,
            long pendingOperations,
            long unknownOperations,
            long pendingCallbacks,
            long callbackConflicts,
            long pendingImports,
            LocalDateTime oldestOperationAt,
            LocalDateTime oldestCallbackAt,
            LocalDateTime oldestImportAt) {}

    public interface StatisticsMapper extends BaseMapper<CimSyncRun> {
        @Select(
                """
                <script>SELECT r.status, COUNT(*) AS count

                FROM cim_sync_run r
                JOIN cim_store_connection c ON c.tenant_no=r.tenant_no AND c.entity_no=r.entity_no
                    AND c.connection_no=r.connection_no AND c.store_no=r.store_no AND c.deleted=0
                JOIN cim_product_profile p ON p.tenant_no=c.tenant_no AND p.profile_no=c.profile_no AND p.deleted=0
                WHERE r.tenant_no='MAIN' AND r.entity_no=#{q.entityNo} AND r.store_no=#{q.storeNo} AND r.deleted=0
                    AND r.created_at &gt;= #{q.from} AND r.created_at &lt; #{q.to}

                <if test="q.providerNo != null">AND p.provider_no=#{q.providerNo}</if>
                <if test="q.productCode != null">AND p.product_code=#{q.productCode}</if>
                <if test="q.connectionNo != null">AND c.connection_no=#{q.connectionNo}</if>
                <if test="q.type != null">AND r.run_type=#{q.type}</if>
                GROUP BY r.status ORDER BY r.status</script>
                """)
        List<StatusCount> syncStates(@Param("q") Window query);

        @Select(
                """
                <script>SELECT COUNT(*) FROM (SELECT p.profile_no

                FROM cim_sync_run r
                JOIN cim_store_connection c ON c.tenant_no=r.tenant_no AND c.entity_no=r.entity_no
                    AND c.connection_no=r.connection_no AND c.store_no=r.store_no AND c.deleted=0
                JOIN cim_product_profile p ON p.tenant_no=c.tenant_no AND p.profile_no=c.profile_no AND p.deleted=0
                WHERE r.tenant_no='MAIN' AND r.entity_no=#{q.entityNo} AND r.store_no=#{q.storeNo} AND r.deleted=0
                    AND r.created_at &gt;= #{q.from} AND r.created_at &lt; #{q.to}

                <if test="q.providerNo != null">AND p.provider_no=#{q.providerNo}</if>
                <if test="q.productCode != null">AND p.product_code=#{q.productCode}</if>
                <if test="q.connectionNo != null">AND c.connection_no=#{q.connectionNo}</if>
                <if test="q.type != null">AND r.run_type=#{q.type}</if>
                GROUP BY p.provider_no,p.profile_no,p.product_code,p.market) cohorts</script>
                """)
        long providerGroups(@Param("q") Window query);

        @Select(
                """
                <script>SELECT p.provider_no,p.profile_no,p.product_code,p.market,COUNT(*) AS total,
                    SUM(CASE WHEN r.status='SUCCEEDED' THEN 1 ELSE 0 END) AS succeeded,
                    SUM(CASE WHEN r.status='PARTIAL' THEN 1 ELSE 0 END) AS partial,
                    SUM(CASE WHEN r.status='FAILED' THEN 1 ELSE 0 END) AS failed,
                    SUM(CASE WHEN r.status='UNKNOWN' THEN 1 ELSE 0 END) AS unknown_count,
                    SUM(CASE WHEN r.status IN ('QUEUED','RUNNING','SUBMITTED') THEN 1 ELSE 0 END) AS pending

                FROM cim_sync_run r
                JOIN cim_store_connection c ON c.tenant_no=r.tenant_no AND c.entity_no=r.entity_no
                    AND c.connection_no=r.connection_no AND c.store_no=r.store_no AND c.deleted=0
                JOIN cim_product_profile p ON p.tenant_no=c.tenant_no AND p.profile_no=c.profile_no AND p.deleted=0
                WHERE r.tenant_no='MAIN' AND r.entity_no=#{q.entityNo} AND r.store_no=#{q.storeNo} AND r.deleted=0
                    AND r.created_at &gt;= #{q.from} AND r.created_at &lt; #{q.to}

                <if test="q.providerNo != null">AND p.provider_no=#{q.providerNo}</if>
                <if test="q.productCode != null">AND p.product_code=#{q.productCode}</if>
                <if test="q.connectionNo != null">AND c.connection_no=#{q.connectionNo}</if>
                <if test="q.type != null">AND r.run_type=#{q.type}</if>
                GROUP BY p.provider_no,p.profile_no,p.product_code,p.market
                ORDER BY p.provider_no,p.profile_no LIMIT #{size} OFFSET #{offset}</script>
                """)
        List<Provider> providerGroupsPage(
                @Param("q") Window query, @Param("offset") long offset, @Param("size") int size);

        @Select(
                """
                <script>SELECT r.import_status AS status, COUNT(*) AS count
                FROM cim_order_link r
                JOIN cim_store_connection c ON c.tenant_no=r.tenant_no AND c.entity_no=r.entity_no
                    AND c.connection_no=r.connection_no AND c.store_no=r.store_no AND c.deleted=0
                JOIN cim_product_profile p ON p.tenant_no=c.tenant_no AND p.profile_no=c.profile_no AND p.deleted=0
                WHERE r.tenant_no='MAIN' AND r.entity_no=#{q.entityNo} AND r.store_no=#{q.storeNo} AND r.deleted=0
                    AND r.created_at &gt;= #{q.from} AND r.created_at &lt; #{q.to}

                <if test="q.providerNo != null">AND p.provider_no=#{q.providerNo}</if>
                <if test="q.productCode != null">AND p.product_code=#{q.productCode}</if>
                <if test="q.connectionNo != null">AND c.connection_no=#{q.connectionNo}</if>
                GROUP BY r.import_status ORDER BY r.import_status</script>
                """)
        List<StatusCount> importStates(@Param("q") Window query);

        @Select(
                """
                <script>SELECT r.status AS status, COUNT(*) AS count
                FROM cim_inbound_event r
                JOIN cim_store_connection c ON c.tenant_no=r.tenant_no AND c.entity_no=r.entity_no
                    AND c.connection_no=r.connection_no AND c.store_no=r.store_no AND c.deleted=0
                JOIN cim_product_profile p ON p.tenant_no=c.tenant_no AND p.profile_no=c.profile_no AND p.deleted=0
                WHERE r.tenant_no='MAIN' AND r.entity_no=#{q.entityNo} AND r.store_no=#{q.storeNo} AND r.deleted=0
                    AND r.created_at &gt;= #{q.from} AND r.created_at &lt; #{q.to}

                <if test="q.providerNo != null">AND p.provider_no=#{q.providerNo}</if>
                <if test="q.productCode != null">AND p.product_code=#{q.productCode}</if>
                <if test="q.connectionNo != null">AND c.connection_no=#{q.connectionNo}</if>
                GROUP BY r.status ORDER BY r.status</script>
                """)
        List<StatusCount> eventStates(@Param("q") Window query);

        @Select(
                """
                <script>SELECT r.classification AS status, COUNT(*) AS count
                FROM cim_inbound_receipt r
                JOIN cim_store_connection c ON c.tenant_no=r.tenant_no AND c.entity_no=r.entity_no
                    AND c.connection_no=r.connection_no AND c.store_no=r.store_no AND c.deleted=0
                JOIN cim_product_profile p ON p.tenant_no=c.tenant_no AND p.profile_no=c.profile_no AND p.deleted=0
                WHERE r.tenant_no='MAIN' AND r.entity_no=#{q.entityNo} AND r.store_no=#{q.storeNo} AND r.deleted=0
                    AND r.received_at &gt;= #{q.from} AND r.received_at &lt; #{q.to}

                <if test="q.providerNo != null">AND p.provider_no=#{q.providerNo}</if>
                <if test="q.productCode != null">AND p.product_code=#{q.productCode}</if>
                <if test="q.connectionNo != null">AND c.connection_no=#{q.connectionNo}</if>
                GROUP BY r.classification ORDER BY r.classification</script>
                """)
        List<StatusCount> receiptStates(@Param("q") Window query);

        @Select(
                """
                    <script>SELECT COUNT(*)
                    FROM cim_store_connection c
                    LEFT JOIN cim_account a ON a.tenant_no=c.tenant_no AND a.entity_no=c.entity_no AND a.account_no=c.account_no AND a.deleted=0
                    LEFT JOIN cim_product_profile p ON p.tenant_no=c.tenant_no AND p.profile_no=c.profile_no AND p.deleted=0
                    LEFT JOIN cim_connection_revision cr ON cr.tenant_no=c.tenant_no AND cr.entity_no=c.entity_no
                    AND cr.connection_no=c.connection_no AND cr.revision_no=c.active_revision_no AND cr.deleted=0
                LEFT JOIN cim_authorization_revision g ON g.tenant_no=c.tenant_no AND g.entity_no=c.entity_no
                        AND g.account_no=a.account_no AND g.authorization_no=a.active_authorization_no AND g.deleted=0
                    WHERE c.tenant_no='MAIN' AND c.entity_no=#{q.entityNo} AND c.store_no=#{q.storeNo} AND c.deleted=0

                    <if test="q.providerNo != null">AND p.provider_no=#{q.providerNo}</if>
                    <if test="q.productCode != null">AND p.product_code=#{q.productCode}</if>
                    <if test="q.connectionNo != null">AND c.connection_no=#{q.connectionNo}</if>
                    </script>
                """)
        long connectionCount(@Param("q") Window query);

        @Select(
                """
                    <script>SELECT c.connection_no,c.profile_no,c.status AS connection_status,a.status AS account_status,
                        p.status AS profile_status,cr.source_owner,g.status AS authorization_status,g.expires_at AS authorization_expires_at,
                        (SELECT s.remote_consistency FROM cim_catalog_scope s WHERE s.tenant_no=c.tenant_no
                            AND s.entity_no=c.entity_no AND s.connection_no=c.connection_no AND s.remote_scope_key='STORE'
                            AND s.deleted=0) AS catalog_consistency,
                        (SELECT COUNT(*) FROM cim_operation o WHERE o.tenant_no=c.tenant_no AND o.entity_no=c.entity_no AND (o.connection_no=c.connection_no OR o.store_no IS NULL AND o.account_no=c.account_no) AND o.deleted=0 AND o.status IN ('QUEUED','RUNNING','SUBMITTED','UNKNOWN','SENDING','EXCHANGING')) AS pending_operations,
                        (SELECT COUNT(*) FROM cim_operation o WHERE o.tenant_no=c.tenant_no AND o.entity_no=c.entity_no AND (o.connection_no=c.connection_no OR o.store_no IS NULL AND o.account_no=c.account_no) AND o.deleted=0 AND o.status='UNKNOWN') AS unknown_operations,
                        (SELECT COUNT(*) FROM cim_inbound_event e WHERE e.tenant_no=c.tenant_no AND e.entity_no=c.entity_no AND e.store_no=c.store_no AND e.connection_no=c.connection_no AND e.deleted=0
                            AND e.status IN ('NEW','PROCESSING','RETRY','WAITING_OWNER')) AS pending_callbacks,
                        (SELECT COUNT(*) FROM cim_inbound_event e WHERE e.tenant_no=c.tenant_no AND e.entity_no=c.entity_no AND e.store_no=c.store_no AND e.connection_no=c.connection_no AND e.deleted=0 AND e.status='CONFLICT') AS callback_conflicts,
                        (SELECT COUNT(*) FROM cim_order_link i WHERE i.tenant_no=c.tenant_no AND i.entity_no=c.entity_no AND i.store_no=c.store_no AND i.connection_no=c.connection_no AND i.deleted=0 AND i.import_status NOT IN ('CONFIRMED','CANCELLED','REJECTED')) AS pending_imports,
                        (SELECT MIN(o.created_at) FROM cim_operation o WHERE o.tenant_no=c.tenant_no AND o.entity_no=c.entity_no AND (o.connection_no=c.connection_no OR o.store_no IS NULL AND o.account_no=c.account_no) AND o.deleted=0 AND o.status IN ('QUEUED','RUNNING','SUBMITTED','UNKNOWN','SENDING','EXCHANGING')) AS oldest_operation_at,
                        (SELECT MIN(e.created_at) FROM cim_inbound_event e WHERE e.tenant_no=c.tenant_no AND e.entity_no=c.entity_no AND e.store_no=c.store_no AND e.connection_no=c.connection_no AND e.deleted=0
                            AND e.status IN ('NEW','PROCESSING','RETRY','WAITING_OWNER','CONFLICT')) AS oldest_callback_at,
                        (SELECT MIN(i.created_at) FROM cim_order_link i WHERE i.tenant_no=c.tenant_no AND i.entity_no=c.entity_no AND i.store_no=c.store_no AND i.connection_no=c.connection_no AND i.deleted=0 AND i.import_status NOT IN ('CONFIRMED','CANCELLED','REJECTED')) AS oldest_import_at
                    FROM cim_store_connection c
                    LEFT JOIN cim_account a ON a.tenant_no=c.tenant_no AND a.entity_no=c.entity_no AND a.account_no=c.account_no AND a.deleted=0
                    LEFT JOIN cim_product_profile p ON p.tenant_no=c.tenant_no AND p.profile_no=c.profile_no AND p.deleted=0
                    LEFT JOIN cim_connection_revision cr ON cr.tenant_no=c.tenant_no AND cr.entity_no=c.entity_no
                    AND cr.connection_no=c.connection_no AND cr.revision_no=c.active_revision_no AND cr.deleted=0
                LEFT JOIN cim_authorization_revision g ON g.tenant_no=c.tenant_no AND g.entity_no=c.entity_no
                        AND g.account_no=a.account_no AND g.authorization_no=a.active_authorization_no AND g.deleted=0
                    WHERE c.tenant_no='MAIN' AND c.entity_no=#{q.entityNo} AND c.store_no=#{q.storeNo} AND c.deleted=0

                    <if test="q.providerNo != null">AND p.provider_no=#{q.providerNo}</if>
                    <if test="q.productCode != null">AND p.product_code=#{q.productCode}</if>
                    <if test="q.connectionNo != null">AND c.connection_no=#{q.connectionNo}</if>
                    ORDER BY c.connection_no LIMIT 200</script>
                """)
        List<HealthRow> connectionHealth(@Param("q") Window query);
    }
}

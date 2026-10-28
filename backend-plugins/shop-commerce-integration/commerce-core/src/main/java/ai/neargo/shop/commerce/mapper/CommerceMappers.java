package ai.neargo.shop.commerce.mapper;

import ai.neargo.shop.commerce.entity.CimAccount;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.IdentityQuery;

import java.util.List;
import ai.neargo.shop.commerce.entity.CimAuthorizationRevision;
import ai.neargo.shop.commerce.entity.CimCatalogScope;
import ai.neargo.shop.commerce.entity.CimCatalogSnapshot;
import ai.neargo.shop.commerce.entity.CimCatalogSource;
import ai.neargo.shop.commerce.entity.CimConnectionRevision;
import ai.neargo.shop.commerce.entity.CimOperation;
import ai.neargo.shop.commerce.entity.CimOperationAttempt;
import ai.neargo.shop.commerce.entity.CimProductProfile;
import ai.neargo.shop.commerce.entity.CimProvider;
import ai.neargo.shop.commerce.entity.CimRequestJournal;
import ai.neargo.shop.commerce.entity.CimResourceMapping;
import ai.neargo.shop.commerce.entity.CimResourceMappingRevision;
import ai.neargo.shop.commerce.entity.CimStoreConnection;
import ai.neargo.shop.commerce.entity.CimSyncPolicy;
import ai.neargo.shop.commerce.entity.CimSyncRun;
import ai.neargo.shop.commerce.entity.CimSyncRunItem;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 只包含本批实际读写资源；保持平台嵌套Mapper规范。 */
public final class CommerceMappers {
    private CommerceMappers() {}

    public interface ProviderMapper extends BaseMapper<CimProvider> {}

    public interface ProductProfileMapper extends BaseMapper<CimProductProfile> {}

    public interface AccountMapper extends BaseMapper<CimAccount> {}

    public interface StoreConnectionMapper extends BaseMapper<CimStoreConnection> {
        /** Two complete matches are enough to reject ambiguity without choosing an arbitrary profile. */
        @Select("""
                SELECT c.connection_no, c.active_revision_no
                FROM cim_store_connection c
                JOIN cim_account a ON a.tenant_no=c.tenant_no AND a.account_no=c.account_no
                    AND a.entity_no=c.entity_no AND a.profile_no=c.profile_no AND a.deleted=0
                JOIN cim_product_profile p ON p.tenant_no=c.tenant_no AND p.profile_no=c.profile_no AND p.deleted=0
                JOIN cim_provider v ON v.tenant_no=c.tenant_no AND v.provider_no=p.provider_no AND v.deleted=0
                JOIN cim_connection_revision r ON r.tenant_no=c.tenant_no AND r.revision_no=c.active_revision_no
                    AND r.connection_no=c.connection_no AND r.entity_no=c.entity_no AND r.deleted=0
                WHERE c.tenant_no='MAIN' AND c.entity_no=#{q.entityNo} AND c.store_no=#{q.storeNo}
                    AND c.external_store_ref=#{q.externalStoreRef} AND a.external_account_ref=#{q.externalAccountRef}
                    AND v.provider_code=#{q.providerCode} AND c.deleted=0 AND c.status='ACTIVE'
                    AND a.status='ACTIVE' AND p.status='VERIFIED' AND v.status='ACTIVE'
                    AND r.source_owner='FOOD' AND r.catalog_authority='LOCAL' AND r.fulfillment_profile='INSTANT'
                LIMIT 2
                """)
        List<CimStoreConnection> findFoodCatalogCandidates(@Param("q") IdentityQuery query);
    }

    public interface ConnectionRevisionMapper extends BaseMapper<CimConnectionRevision> {}

    public interface AuthorizationRevisionMapper extends BaseMapper<CimAuthorizationRevision> {}

    public interface OperationMapper extends BaseMapper<CimOperation> {}

    public interface OperationAttemptMapper extends BaseMapper<CimOperationAttempt> {}

    public interface CatalogScopeMapper extends BaseMapper<CimCatalogScope> {}

    public interface CatalogSourceMapper extends BaseMapper<CimCatalogSource> {}

    public interface SyncPolicyMapper extends BaseMapper<CimSyncPolicy> {}

    public interface CatalogSnapshotMapper extends BaseMapper<CimCatalogSnapshot> {}

    public interface SyncRunMapper extends BaseMapper<CimSyncRun> {}

    public interface SyncRunItemMapper extends BaseMapper<CimSyncRunItem> {}

    public interface ResourceMappingMapper extends BaseMapper<CimResourceMapping> {}

    public interface ResourceMappingRevisionMapper extends BaseMapper<CimResourceMappingRevision> {}

    public interface RequestJournalMapper extends BaseMapper<CimRequestJournal> {
        @Insert(
                "INSERT INTO"
                    + " cim_request_journal(tenant_no,request_no,entity_no,endpoint_key,key_hash,actor_no,request_hash,status,scope_kind,scope_key,created_by,updated_by)"
                    + " VALUES"
                    + " ('MAIN',#{r.requestNo},#{r.entityNo},#{r.endpointKey},#{r.keyHash},#{r.actorNo},#{r.requestHash},'PENDING',#{r.scopeKind},#{r.scopeKey},#{r.actorNo},#{r.actorNo})"
                    + " ON DUPLICATE KEY UPDATE request_no=request_no")
        int insertOrKeep(@Param("r") CimRequestJournal journal);

        @Select(
                "SELECT * FROM cim_request_journal WHERE tenant_no='MAIN' AND scope_kind=#{kind}"
                    + " AND scope_key=#{scope} AND endpoint_key=#{endpoint} AND key_hash=#{key} AND"
                    + " deleted=0 FOR UPDATE")
        CimRequestJournal lock(
                @Param("kind") String kind,
                @Param("scope") String scope,
                @Param("endpoint") String endpoint,
                @Param("key") String key);
    }
}

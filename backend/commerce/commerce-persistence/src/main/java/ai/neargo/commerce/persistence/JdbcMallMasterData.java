package ai.neargo.commerce.persistence;

import ai.neargo.commerce.api.catalog.BusinessEndDraft;
import ai.neargo.commerce.api.catalog.CatalogErrorCode;
import ai.neargo.commerce.api.catalog.CatalogException;
import ai.neargo.commerce.core.catalog.CatalogState;
import ai.neargo.commerce.core.catalog.CatalogWriteContext;
import java.sql.Timestamp;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

final class JdbcMallMasterData {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private JdbcMallMasterData() {}

    static void saveBusinessType(JdbcTemplate dictionary, String tenant, CatalogState.Type before,
            CatalogState.Type after, CatalogWriteContext context) {
        var draft = after.draft();
        var now = Timestamp.from(context.occurredAt());
        var actor = context.principalNo();
        String status = draft.enabled() ? "ENABLED" : "DISABLED";
        if (before == null) {
            try {
                dictionary.update("""
                        INSERT INTO sys_dict_item (tenant_no,dict_type,item_level,item_code,name,sort_no,status,
                            attrs,source,version,deleted,created_at,created_by,updated_at,updated_by)
                        VALUES (?,'BUSINESS_TYPE','TYPE',?,?,?,?,JSON_OBJECT('plan_catalog_version',0,'change_reason',?),
                            'OFFICIAL',1,0,?,?,?,?)
                        """, tenant, draft.typeCode(), draft.name(), draft.sortOrder(), status, draft.reason(),
                        now, actor, now, actor);
            } catch (DuplicateKeyException duplicate) {
                throw conflict(draft.typeCode());
            }
        } else {
            int changed = dictionary.update("""
                    UPDATE sys_dict_item SET name=?,sort_no=?,status=?,attrs=JSON_SET(COALESCE(attrs,JSON_OBJECT()),'$.change_reason',?),version=version+1,
                        updated_at=?,updated_by=? WHERE tenant_no=? AND dict_type='BUSINESS_TYPE'
                        AND item_level='TYPE' AND item_code=? AND version=? AND deleted=0
                    """, draft.name(), draft.sortOrder(), status, draft.reason(), now, actor,
                    tenant, draft.typeCode(), before.version());
            if (changed != 1) throw conflict(draft.typeCode());
        }
    }

    static void save(DataSource mall, String tenant, CatalogState before,
            CatalogState after, CatalogWriteContext context) {
        var jdbc = new JdbcTemplate(mall);
        after.ends.forEach((code, next) -> {
            var old = before.ends.get(code);
            if (!Objects.equals(old, next)) saveEnd(jdbc, tenant, old, next, context);
        });
    }

    private static void saveEnd(JdbcTemplate jdbc, String tenant, CatalogState.End old,
            CatalogState.End next, CatalogWriteContext context) {
        BusinessEndDraft d = next.draft();
        var at = Timestamp.from(context.occurredAt());
        String release = switch (d.releaseStatus()) {
            case PLANNED -> "DEV";
            case VERIFIED -> "GA";
            case RETIRED -> "RETIRED";
        };
        String types = JSON.writeValueAsString(d.allowedBusinessTypes());
        if (old == null) {
            jdbc.update("""
                    INSERT INTO mall_end_entitlement (tenant_no,end_code,entitlement_code,name,scope,
                        allowed_business_types,release_status,sale_enabled,bootstrap_required,enabled,
                        client_id,route_policy_ref,acceptance_ref,version,deleted,created_at,updated_at,created_by,updated_by)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,1,0,?,?,?,?)
                    """, tenant,d.code(),d.entitlementCode(),d.name(),d.scope().name(),types,release,d.saleEnabled(),
                    d.bootstrapRequired(),d.enabled(),d.clientId(),d.routePolicyRef(),d.acceptanceRef(),at,at,
                    context.principalNo(),context.principalNo());
        } else {
            int changed = jdbc.update("""
                    UPDATE mall_end_entitlement SET name=?,scope=?,allowed_business_types=?,
                        release_status=CASE WHEN release_status='BETA' AND ?='DEV' THEN 'BETA' ELSE ? END,
                        sale_enabled=?,bootstrap_required=?,enabled=?,client_id=?,route_policy_ref=?,acceptance_ref=?,
                        version=version+1,updated_at=?,updated_by=?
                    WHERE tenant_no=? AND end_code=? AND version=? AND deleted=0
                    """,d.name(),d.scope().name(),types,release,release,d.saleEnabled(),d.bootstrapRequired(),
                    d.enabled(),d.clientId(),d.routePolicyRef(),d.acceptanceRef(),at,context.principalNo(),
                    tenant,d.code(),old.version());
            if (changed != 1) throw conflict(d.code());
        }
    }

    private static CatalogException conflict(String code) {
        return new CatalogException(CatalogErrorCode.VERSION_CONFLICT, "stale catalog row: " + code);
    }
}

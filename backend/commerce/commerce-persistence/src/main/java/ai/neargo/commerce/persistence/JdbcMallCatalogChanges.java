package ai.neargo.commerce.persistence;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.port.CommerceEvent;
import ai.neargo.commerce.core.catalog.CatalogChangeJournal;
import java.sql.Timestamp;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

final class JdbcMallCatalogChanges implements CatalogChangeJournal {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate mall;
    private final JdbcTemplate dictionary;
    private final TransactionTemplate dictionaryTransaction;

    JdbcMallCatalogChanges(JdbcTemplate mall, DataSource foundation) {
        this.mall = mall;
        dictionary = new JdbcTemplate(foundation);
        dictionaryTransaction = new TransactionTemplate(new DataSourceTransactionManager(foundation));
        dictionaryTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void append(CommerceEvent change, IndustryPublicationCommand command, PublicationResult result) {
        mall.update("""
                INSERT INTO mall_catalog_change
                  (tenant_no,change_no,aggregate_kind,aggregate_no,change_type,detail,occurred_at,
                   catalog_version,publication_command,publication_result,dictionary_pending)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, change.tenantNo(), change.eventNo(), change.aggregateType(), change.aggregateNo(),
                change.eventType(), change.payloadJson(), Timestamp.from(change.occurredAt()),
                command == null ? null : result.catalogVersion(),
                command == null ? null : JSON.writeValueAsString(command),
                result == null ? null : JSON.writeValueAsString(result), command != null);
    }

    public Optional<PublicationResult> publication(String tenant, IndustryPublicationCommand command) {
        return mall.query("""
                SELECT publication_command,publication_result FROM mall_catalog_change
                WHERE tenant_no=? AND aggregate_kind='CATALOG_TYPE' AND aggregate_no=? AND catalog_version=?
                FOR UPDATE
                """, (rs, index) -> {
                    if (!JSON.readValue(rs.getString(1), IndustryPublicationCommand.class).equals(command))
                        throw new CatalogException(CatalogErrorCode.VERSION_CONFLICT,
                                "catalog generation already published by another command");
                    return JSON.readValue(rs.getString(2), PublicationResult.class);
                }, tenant, command.typeCode(), command.expectedCatalogVersion() + 1).stream().findFirst();
    }

    public void finishPending(String tenant) {
        var pending = mall.queryForList("""
                SELECT id,aggregate_no,change_no,catalog_version,detail->>'$.actor' AS actor
                FROM mall_catalog_change WHERE tenant_no=? AND dictionary_pending=1 ORDER BY id FOR UPDATE
                """, tenant);
        for (var row : pending) {
            String type = (String) row.get("aggregate_no"), change = (String) row.get("change_no");
            long target = ((Number) row.get("catalog_version")).longValue();
            dictionaryTransaction.executeWithoutResult(status -> {
                int changed = dictionary.update("""
                        UPDATE sys_dict_item SET attrs=JSON_SET(attrs,
                            '$.plan_catalog_version',CAST(? AS UNSIGNED),'$.plan_catalog_publication_no',?),
                            version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(3)
                        WHERE tenant_no=? AND dict_type='BUSINESS_TYPE' AND item_level='TYPE'
                          AND item_code=? AND deleted=0
                          AND CAST(attrs->>'$.plan_catalog_version' AS UNSIGNED)=?
                        """, target, change, row.get("actor"), tenant, type, target - 1);
                if (changed == 0 && dictionary.queryForList("""
                        SELECT item_code FROM sys_dict_item WHERE tenant_no=? AND dict_type='BUSINESS_TYPE'
                          AND item_level='TYPE' AND item_code=? AND deleted=0
                          AND CAST(attrs->>'$.plan_catalog_version' AS UNSIGNED)=?
                          AND attrs->>'$.plan_catalog_publication_no'=? FOR SHARE
                        """, String.class, tenant, type, target, change).isEmpty())
                    throw new CatalogException(CatalogErrorCode.VERSION_CONFLICT,
                            "dictionary publication version conflicts with mall receipt");
            });
            mall.update("UPDATE mall_catalog_change SET dictionary_pending=0 WHERE id=? AND tenant_no=?",
                    row.get("id"), tenant);
        }
    }
}

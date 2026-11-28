package ai.neargo.shop.product.service.impl;

import ai.neargo.shop.common.config.ModuleDataSources;
import ai.neargo.shop.product.entity.PrdSpecUsage;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 交易库分别保存维度范围和取值范围时，按建品模板读侧使用的字段投影。 */
@Component
@ConditionalOnProperty(name = "shop.product.split-spec-template-reads", havingValue = "true")
class SplitSpecTemplateReads {
    private static final String DIMENSIONS = """
            SELECT id, dim_no, scope_ref, usage_type, is_primary, sort, status
              FROM prd_spec_usage
             WHERE tenant_no='MAIN' AND deleted=0 AND status='ACTIVE'
            """;
    private static final BeanPropertyRowMapper<PrdSpecUsage> ROW = BeanPropertyRowMapper.newInstance(PrdSpecUsage.class);
    private final JdbcTemplate jdbc;

    SplitSpecTemplateReads(@Qualifier(ModuleDataSources.PRODUCT_DATA_SOURCE) DataSource source) {
        jdbc = new JdbcTemplate(source);
    }

    List<PrdSpecUsage> bindings(String category) {
        return jdbc.query(DIMENSIONS + """
                 AND scope_type='CATEGORY' AND scope_ref=? AND (entity_no IS NULL OR entity_no='')
                 ORDER BY id
                """, ROW, category);
    }

    List<PrdSpecUsage> recommendations(String type) {
        String sql = DIMENSIONS + " AND scope_type='CATEGORY_TYPE' AND (entity_no IS NULL OR entity_no='')";
        return type == null || type.isBlank() ? jdbc.query(sql + " ORDER BY id", ROW)
                : jdbc.query(sql + " AND scope_ref IN (?, '*') ORDER BY id", ROW, type);
    }

    List<PrdSpecUsage> favorites(String merchant) {
        return jdbc.query(DIMENSIONS + """
                 AND scope_type='MERCHANT' AND scope_ref=? AND entity_no=? ORDER BY id
                """, ROW, merchant, merchant);
    }

    List<PrdSpecUsage> subset(String category) {
        return jdbc.query("""
                SELECT u.dim_no, u.value_no, u.label_override, COALESCE(u.sort, v.sort) AS sort,
                       CASE WHEN u.enabled=0 THEN 'DISABLED' ELSE 'ACTIVE' END AS status
                  FROM prd_spec_value_usage u
                  LEFT JOIN prd_spec_value v ON v.tenant_no=u.tenant_no AND v.dim_no=u.dim_no AND v.value_no=u.value_no
                 WHERE u.tenant_no='MAIN' AND u.deleted=0 AND (u.entity_no IS NULL OR u.entity_no='')
                   AND u.category_no=? AND u.value_no IS NOT NULL ORDER BY u.id
                """, ROW, category);
    }

    List<PrdSpecUsage> overrides(String merchant, String category) {
        // Overrides 按取值 code 合并。全局偏好先读，具体类目的记录优先。
        return jdbc.query("""
                SELECT u.dim_no, v.code AS value_no, u.label_override, u.sort,
                       CASE WHEN u.enabled=0 THEN 'DISABLED' ELSE 'ACTIVE' END AS status
                  FROM prd_spec_value_usage u JOIN prd_spec_value v
                    ON v.tenant_no=u.tenant_no AND v.dim_no=u.dim_no AND v.value_no=u.value_no AND v.deleted=0
                   AND (v.scope='PLATFORM' OR (v.scope='MERCHANT' AND v.entity_no=?))
                 WHERE u.tenant_no='MAIN' AND u.deleted=0 AND u.entity_no=?
                   AND (u.category_no IS NULL OR u.category_no='' OR u.category_no=?)
                 ORDER BY CASE WHEN u.category_no=? THEN 1 ELSE 0 END, u.id
                """, ROW, merchant, merchant, category, category);
    }

    List<PrdSpecUsage> labels(String merchant, String dim) {
        return jdbc.query("""
                SELECT u.value_no, u.label_override
                  FROM prd_spec_value_usage u JOIN prd_spec_value v
                    ON v.tenant_no=u.tenant_no AND v.value_no=u.value_no AND v.dim_no=u.dim_no
                   AND v.deleted=0 AND v.status='ACTIVE'
                   AND (v.scope='PLATFORM' OR (v.scope='MERCHANT' AND v.entity_no=?))
                 WHERE u.tenant_no='MAIN' AND u.deleted=0 AND u.dim_no=? AND u.label_override IS NOT NULL
                   AND (u.enabled IS NULL OR u.enabled<>0)
                   AND (u.entity_no IS NULL OR u.entity_no='' OR u.entity_no=?)
                 ORDER BY CASE WHEN u.entity_no=? THEN 0 ELSE 1 END, u.id
                """, ROW, merchant, dim, merchant, merchant);
    }
}

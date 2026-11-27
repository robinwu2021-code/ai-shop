package ai.neargo.shop.merchant.plan.impl;

import ai.neargo.commerce.api.common.Money;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.plan.EntityPlanPurchaseService.CatalogBinding;
import org.springframework.jdbc.core.JdbcTemplate;

/** Resolves the explicit sale version and all rights of its single plan component. */
final class EntityPlanOfferCatalog {
  private final JdbcTemplate mall;

  EntityPlanOfferCatalog(JdbcTemplate mall) { this.mall = mall; }

  CatalogBinding resolve(String tenant, String version, String tier, Money price, int days, long at) {
    var rows = mall.queryForList("""
        SELECT s.sku_no, p.version_no, e.entitlement_code
        FROM mall_sku_version v
        JOIN mall_sku s ON s.tenant_no=v.tenant_no AND s.sku_no=v.sku_no AND s.current_version_no=v.sku_version_no
        JOIN mall_product g ON g.tenant_no=s.tenant_no AND g.product_no=s.product_no
        JOIN mall_sku_component c ON c.tenant_no=v.tenant_no AND c.sku_version_no=v.sku_version_no AND c.deleted=0
        JOIN sys_industry_plan p ON p.tenant_no=c.tenant_no AND p.version_no=c.plan_version_no
        JOIN sys_plan_version_end e ON e.tenant_no=p.tenant_no AND e.plan_version_no=p.version_no AND e.deleted=0
        WHERE v.tenant_no=? AND v.sku_version_no=? AND v.status='PUBLISHED' AND s.sale_status='ON_SALE'
          AND g.sale_status='ON_SALE' AND v.deleted=0 AND s.deleted=0 AND g.deleted=0 AND p.deleted=0
          AND v.currency=? AND v.sale_price_minor=? AND c.component_type='PLAN' AND c.unit_count=1
          AND c.period_days=? AND c.is_gift=0 AND p.tier_code=? AND p.sale_status='ON_SALE' AND p.status='ACTIVE'
          AND (p.effective_from IS NULL OR p.effective_from<=?) AND (p.effective_to IS NULL OR p.effective_to>?)
          AND NOT EXISTS (SELECT 1 FROM mall_sku_component x WHERE x.tenant_no=v.tenant_no
            AND x.sku_version_no=v.sku_version_no AND x.deleted=0 AND x.component_no<>c.component_no)
        ORDER BY e.entitlement_code
        """,tenant,version,price.currency(),price.minor(),days,tier,at,at);
    if (rows.isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
    var first = rows.getFirst();
    return new CatalogBinding((String)first.get("sku_no"),version,(String)first.get("version_no"),
        rows.stream().map(r -> (String)r.get("entitlement_code")).toList());
  }
}

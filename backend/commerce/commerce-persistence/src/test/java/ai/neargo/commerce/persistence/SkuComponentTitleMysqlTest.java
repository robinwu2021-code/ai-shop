package ai.neargo.commerce.persistence;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class SkuComponentTitleMysqlTest {
    @Test
    void componentTitleRoundTripsAndEditsAsAMemberChange() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new JdbcCatalogFixture(db);
            f.setup();
            f.plans();
            f.product();
            var plan = f.component("a", ComponentKind.PLAN, "pro-1", null, null, 365, 1, 100, false);
            var named = new SkuComponent(null, plan.kind(), plan.planVersionNo(), null, null,
                    plan.periodDays(), 1, 100, false, plan.activationPolicy(), "零售專業版・年付");
            f.facade.saveSku(f.ops, f.sku("sku-1", List.of(named)));
            var v = f.facade.catalog(f.ops, "RETAIL").skuVersions().getFirst();
            assertEquals("零售專業版・年付", v.draft().components().getFirst().title());
            // rename on the draft: same component number, new title persisted
            var c = v.draft().components().getFirst();
            var renamed = new SkuComponent(c.componentNo(), c.kind(), c.planVersionNo(), null, null,
                    c.periodDays(), 1, 100, false, c.activationPolicy(), "PRO 年付");
            var d = v.draft();
            f.facade.saveSku(f.ops, new SkuDraft(d.productNo(), d.skuNo(), d.skuVersionNo(), d.title(),
                    d.listPrice(), d.price(), d.allowedBusinessTypes(), d.refundPolicySnapshot(),
                    List.of(renamed), d.maxPerOrder(), v.version(), "rename"));
            assertEquals("PRO 年付", db.jdbc.queryForObject(
                    "SELECT title FROM mall_sku_component WHERE component_no=?", String.class, c.componentNo()));
            assertEquals("PRO 年付",
                    f.facade.catalog(f.ops, "RETAIL").skuVersions().getFirst().draft().components().getFirst().title());
        }
    }
}

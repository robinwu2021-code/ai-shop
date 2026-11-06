package ai.neargo.commerce.core.catalog;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class SkuComponentTitleTest {
    static SkuComponent titled(SkuComponent c, String title) {
        return new SkuComponent(c.componentNo(), c.kind(), c.planVersionNo(), c.entitlementCode(),
                c.hardwareSkuNo(), c.periodDays(), c.unitCount(), c.allocationWeight(), c.gift(),
                c.activationPolicy(), title);
    }

    static SkuDraft withComponents(SkuDraft d, List<SkuComponent> cs) {
        return new SkuDraft(d.productNo(), d.skuNo(), d.skuVersionNo(), d.title(), d.listPrice(),
                d.price(), d.allowedBusinessTypes(), d.refundPolicySnapshot(), cs, d.maxPerOrder(),
                d.expectedVersion(), d.reason());
    }

    CatalogFixture ready() {
        var f = new CatalogFixture();
        f.setup();
        f.plans();
        f.product();
        return f;
    }

    @Test
    void componentTitleIsKeptWithTheVersionAndOptional() {
        var f = ready();
        var plan = f.component("a", ComponentKind.PLAN, "pro-1", null, null, 365, 1, 100, false);
        var hw = f.component("b", ComponentKind.HARDWARE, null, null, "HW-PRINTER-80", null, 1, 100, false);
        f.facade.saveSku(f.ops, withComponents(f.sku(), List.of(titled(plan, "零售專業版・年付"), hw)));
        var saved = f.facade.catalog(f.ops, "RETAIL").skuVersions().getFirst().draft().components();
        assertEquals("零售專業版・年付", saved.get(0).title());
        assertNull(saved.get(1).title());
        // the legacy 10-argument constructor still means "no title"
        assertNull(plan.title());
    }

    @Test
    void blankOrOverlongTitleIsRejected() {
        for (String bad : List.of(" ", "x".repeat(65))) {
            var f = ready();
            var plan = f.component("a", ComponentKind.PLAN, "pro-1", null, null, 365, 1, 100, false);
            var e = assertThrows(CatalogException.class,
                    () -> f.facade.saveSku(f.ops, withComponents(f.sku(), List.of(titled(plan, bad)))));
            assertEquals(CatalogErrorCode.INVALID_ARGUMENT, e.code());
        }
    }
}

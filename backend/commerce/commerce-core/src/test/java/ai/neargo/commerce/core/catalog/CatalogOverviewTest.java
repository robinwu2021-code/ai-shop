package ai.neargo.commerce.core.catalog;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;

import org.junit.jupiter.api.Test;

class CatalogOverviewTest {
    @Test
    void emptyCatalogStillHasAuthorizedManagementBootstrap() {
        var f = new CatalogFixture();
        assertTrue(f.facade.overview(f.ops).businessTypes().isEmpty());
        f.setup();
        assertEquals(
                "RETAIL", f.facade.overview(f.ops).businessTypes().getFirst().draft().typeCode());
        assertThrows(
                CatalogException.class,
                () ->
                        f.facade.overview(
                                new ActorContext(
                                        "tenant-a", SubjectKind.ACCOUNT, "buyer", "trusted")));
        f.capabilities.remove("commerce:catalog:read");
        assertThrows(CatalogException.class, () -> f.facade.overview(f.ops));
    }
}

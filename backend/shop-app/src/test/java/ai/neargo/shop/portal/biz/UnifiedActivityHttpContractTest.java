package ai.neargo.shop.portal.biz;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.shop.portal.ops.OpsVersionedActivityController;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;

/** Public bodies remain flat; owner context and readiness cannot be supplied by an HTTP caller. */
class UnifiedActivityHttpContractTest {
    @Test void draftBodyIsFlatAndCarriesPresetReference() {
        var fields = java.util.Arrays.stream(BizVersionedActivityController.DraftRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertTrue(fields.containsAll(java.util.List.of("templateCode", "templateVersion", "rule", "schedule")));
        assertFalse(fields.contains("definition")); assertFalse(fields.contains("entityNo"));
        assertFalse(fields.contains("ready")); assertFalse(fields.contains("capabilities"));
    }
    @Test void governanceReusesExistingRouteAndUpdatePermission() throws Exception {
        var method = OpsVersionedActivityController.class.getMethod("stop", String.class,
                OpsVersionedActivityController.StopRequest.class, String.class);
        assertArrayEquals(new String[]{"model=unified"}, method.getAnnotation(PostMapping.class).params());
        assertTrue(method.getAnnotation(PreAuthorize.class).value().contains("marketing:campaign:update"));
        assertEquals("Idempotency-Key", method.getParameters()[2].getAnnotation(RequestHeader.class).value());
    }
}

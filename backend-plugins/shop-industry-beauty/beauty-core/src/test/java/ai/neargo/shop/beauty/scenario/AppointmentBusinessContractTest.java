package ai.neargo.shop.beauty.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import ai.neargo.shop.auth.BizPerms;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AppointmentBusinessContractTest {
    @Test
    void bookingPermissionsAreExplicitlyAssignableWithoutBuiltinStaffGrants() {
        assertEquals("查看预约", BizPerms.LABELS.get("biz:booking:view"));
        assertEquals("创建、调整及取消预约", BizPerms.LABELS.get("biz:booking:manage"));
        for (String role : Set.of(BizPerms.MANAGER, BizPerms.CLERK, BizPerms.CS)) {
            assertFalse(BizPerms.can(Set.of(role), "biz:booking:manage"));
        }
    }
    @Test
    void nativeServiceAndOwnerNeutralBoundariesExist() throws Exception {
        for (String name : Set.of("AppointmentService", "AppointmentAvailabilityService",
                "ServiceOperationQueryService")) {
            assertNotNull(Class.forName("ai.neargo.shop.beauty.service." + name));
        }
        assertNotNull(Class.forName("ai.neargo.shop.spi.resource.ResourceBookingPort"));
        assertNotNull(Class.forName("ai.neargo.shop.spi.resource.ResourceCandidatesPort"));
    }
}

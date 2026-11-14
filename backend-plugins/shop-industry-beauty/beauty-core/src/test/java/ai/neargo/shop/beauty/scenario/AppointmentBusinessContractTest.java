package ai.neargo.shop.beauty.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import ai.neargo.shop.auth.BizPerms;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AppointmentBusinessContractTest {
    @Test
    void bookingPermissionsAreExplicitlyAssignableWithoutBuiltinStaffGrants() {
        assertEquals("查看预约", BizPerms.LABELS.get("biz:booking:view"));
        assertEquals("创建、调整及取消预约", BizPerms.LABELS.get("biz:booking:manage"));
        // 2026-11-09 产品定案（TDD-B端门店功能矩阵与方案准入 §4-1、迁移 V640）：店长默认可看可管、店员默认只看；
        // 其余岗位仍不默认授予，管理预约要店主显式勾选
        assertTrue(BizPerms.can(Set.of(BizPerms.MANAGER), "biz:booking:manage"));
        assertTrue(BizPerms.can(Set.of(BizPerms.CLERK), "biz:booking:view"));
        for (String role : Set.of(BizPerms.CLERK, BizPerms.CS, BizPerms.PICKER, BizPerms.COURIER)) {
            assertFalse(BizPerms.can(Set.of(role), "biz:booking:manage"));
        }
        for (String role : Set.of(BizPerms.CS, BizPerms.PICKER, BizPerms.COURIER)) {
            assertFalse(BizPerms.can(Set.of(role), "biz:booking:view"));
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

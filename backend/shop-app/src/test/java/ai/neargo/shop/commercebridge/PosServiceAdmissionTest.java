package ai.neargo.shop.commercebridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PosServiceAdmissionTest {
    @Test
    void requiresServiceRightsForNewOperationsButKeepsReceiptRecoveryReachable() {
        assertEquals("SVC_BOOKING", PosServiceAdmission.featureCode("GET", "/biz/x/beauty/appointments", "SERVICE"));
        assertEquals("SVC_CATALOG", PosServiceAdmission.featureCode("POST", "/biz/x/beauty/store-policies/versions",
                "SERVICE"));
        assertEquals("SVC_SCHEDULE", PosServiceAdmission.featureCode("GET", "/biz/personnel", "SERVICE"));
        assertEquals("SVC_COUNTER_CASH", PosServiceAdmission.featureCode("POST", "/biz/service-orders/counter-quotes",
                "SERVICE"));
        assertEquals("SVC_TASK", PosServiceAdmission.featureCode("GET", "/biz/x/beauty/work-orders/W1/tasks/T1/execution", "SERVICE"));
        assertEquals("SVC_TASK", PosServiceAdmission.featureCode("POST", "/biz/x/beauty/work-orders/W1/tasks/T1/start", "SERVICE"));
        assertEquals("SVC_TASK", PosServiceAdmission.featureCode("POST", "/biz/x/beauty/work-orders/W1/tasks/T1/workflow", "SERVICE"));
        assertEquals("SVC_TASK", PosServiceAdmission.featureCode("POST", "/biz/x/beauty/work-orders/W1/tasks/T1/operations/O1/cancel", "SERVICE"));
        assertNull(PosServiceAdmission.featureCode("POST", "/biz/x/beauty/work-orders/W1/tasks/T1/operations/O1/recover", "SERVICE"));
        assertNull(PosServiceAdmission.featureCode("GET", "/biz/personnel", "RETAIL"));
        assertNull(PosServiceAdmission.featureCode("GET", "/biz/x/beauty/work-orders/W1", "SERVICE"));
        assertNull(PosServiceAdmission.featureCode("POST", "/biz/service-orders/O1/manual-receipts", "SERVICE"));
        assertNull(PosServiceAdmission.featureCode("GET", "/biz/service-orders/counter-quotes/Q1/state", "SERVICE"));
    }
}

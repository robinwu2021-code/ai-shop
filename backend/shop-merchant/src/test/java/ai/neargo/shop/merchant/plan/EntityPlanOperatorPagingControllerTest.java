package ai.neargo.shop.merchant.plan;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.merchant.api.ops.OpsPlanPurchaseController;
import ai.neargo.shop.merchant.plan.EntityPlanPurchaseService.Actor;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class EntityPlanOperatorPagingControllerTest {
  @Test void keepsLegacyCallsAndRoutesOnlyCursorRequestsToBoundedReads() throws Exception {
    var service = mock(EntityPlanPurchaseService.class);
    var audit = mock(AuditLogPort.class);
    var controller = new OpsPlanPurchaseController(service, audit);
    try (var identity = mockStatic(SecurityUtils.class)) {
      identity.when(SecurityUtils::currentUserNo).thenReturn("OP");
      var actor = new Actor("OP", "E1", null, true);
      controller.orders("E1", null, null);
      verify(service).orders(actor);
      controller.orders("E1", "O020", null);
      verify(service).operatorOrders(actor, "O020", 20);
      controller.orders("E1", null, 50);
      verify(service).operatorOrders(actor, null, 50);
      verifyNoInteractions(audit);
    }
    assertEquals("@perm.can('merchant:merchant:read')", OpsPlanPurchaseController.class
        .getMethod("orders", String.class, String.class, Integer.class).getAnnotation(PreAuthorize.class).value());
  }
}

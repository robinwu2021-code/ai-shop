package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.api.mall.reporting.PurchaseOrderQueryPort;
import ai.neargo.shop.auth.PermChecker;
import ai.neargo.shop.auth.LivePermResolver;
import ai.neargo.shop.merchant.plan.EntityPlanPurchaseService;
import ai.neargo.shop.portal.ops.OpsPlatformMallController;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformMallReportingWiringTest {
  AnnotationConfigApplicationContext context(String profile,boolean http) {
    var c=new AnnotationConfigApplicationContext();
    c.getEnvironment().setActiveProfiles(profile);
    c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",Map.of("commerce.enabled","true","commerce.mall.enabled","true","commerce.mall.payment.enabled","true","commerce.mall.offline.enabled","true","commerce.mall.http.enabled",Boolean.toString(http))));
    c.register(PlatformMallReporting.class,OpsPlatformMallController.class);
    return c;
  }
  @Test void disabledHttpNeedsNoSourceOwners(){try(var c=context("ops",false)){c.refresh();assertFalse(c.containsBean("platformMallReporting"));assertFalse(c.containsBean("opsPlatformMallController"));}}
  @Test void jobDeploymentNeedsNoHttpSourceOwners(){try(var c=context("job",true)){c.refresh();assertFalse(c.containsBean("platformMallReporting"));assertFalse(c.containsBean("opsPlatformMallController"));}}
  @Test void opsHttpWiresRealPermissionCheckerAndOptionalCashOwner(){try(var c=context("ops",true)){
    c.registerBean(PurchaseOrderQueryPort.class,()->mock(PurchaseOrderQueryPort.class));
    c.registerBean(EntityPlanPurchaseService.class,()->mock(EntityPlanPurchaseService.class));
    c.registerBean("perm",PermChecker.class,()->new PermChecker(c.getBeanProvider(LivePermResolver.class)));
    c.register(PlatformMallAccess.class);c.refresh();
    assertNotNull(c.getBean(OpsPlatformMallController.class));assertNotNull(c.getBean(PlatformMallReporting.class));
  }}
}

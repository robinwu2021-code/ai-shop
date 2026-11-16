package ai.neargo.shop.merchant;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.merchant.dto.EntityStoresVO;
import ai.neargo.shop.merchant.dto.EntityVO;
import ai.neargo.shop.merchant.service.MerchantEntityService;
import ai.neargo.shop.merchant.service.MerchantPlanService;
import ai.neargo.shop.merchant.service.impl.StoreCreationAccessServiceImpl;
import java.util.List;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class StoreCreationChoicesTest {
 @Test void existingActiveEntityAllowsAnotherButPendingOrFullQuotaDoesNot() {
  var user=LoginUser.merchantByUser("OWNER","owner");var entities=mock(MerchantEntityService.class);
  var service=new StoreCreationAccessServiceImpl(entities,mock(MerchantPlanService.class),true);
  var active=new EntityStoresVO(new EntityVO("E1","Entity","ACTIVE",true,null,0,true,true),List.of());
  when(entities.myStores(user)).thenReturn(List.of(active));
  assertTrue(service.access(user).canQuickStart());
  var pending=new EntityStoresVO(new EntityVO("E1","Entity","PENDING_LICENSE",false,null,0,true,true),List.of());
  when(entities.myStores(user)).thenReturn(List.of(pending));
  assertFalse(service.access(user).canQuickStart());assertTrue(service.access(user).canResumeQuickStart());
  when(entities.myStores(user)).thenReturn(Collections.nCopies(5,active));
  assertFalse(service.access(user).canQuickStart());
 }
}

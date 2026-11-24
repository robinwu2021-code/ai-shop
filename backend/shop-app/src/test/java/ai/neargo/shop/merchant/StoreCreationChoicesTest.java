package ai.neargo.shop.merchant;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.merchant.dto.EntityStoresVO;
import ai.neargo.shop.merchant.dto.EntityVO;
import ai.neargo.shop.merchant.dto.StoreVO;
import ai.neargo.shop.spi.user.MerchantQueryPort;
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
  var service=new StoreCreationAccessServiceImpl(entities,mock(MerchantPlanService.class),mock(MerchantQueryPort.class),true);
  var active=new EntityStoresVO(new EntityVO("E1","Entity","ACTIVE",true,null,0,true,true),List.of());
  when(entities.myStores(user)).thenReturn(List.of(active));
  assertTrue(service.access(user).canQuickStart());
  // 待补证照只有「已经开出门店」时才挡快速开店、允许续开（09eba90fd 起）
  var pending=new EntityStoresVO(new EntityVO("E1","Entity","PENDING_LICENSE",false,null,0,true,true),List.of(mock(ai.neargo.shop.merchant.dto.StoreVO.class)));
  when(entities.myStores(user)).thenReturn(List.of(pending));
  assertFalse(service.access(user).canQuickStart());assertTrue(service.access(user).canResumeQuickStart());
  when(entities.myStores(user)).thenReturn(Collections.nCopies(5,active));
  assertFalse(service.access(user).canQuickStart());
 }
 @Test void firstStoreChoiceRequiresActiveStorelessEntityOwnedByPersonalAccount() {
  var user = LoginUser.merchantByUser("OWNER", "owner");
  var merchant = LoginUser.merchant("ACCOUNT", "owner");
  var entities = mock(MerchantEntityService.class);
  var plans = mock(MerchantPlanService.class);
  var directory = mock(MerchantQueryPort.class);
  var service = new StoreCreationAccessServiceImpl(entities, plans, directory, true);
  var eligible = new EntityStoresVO(new EntityVO("E1","Entity","ACTIVE",true,null,0,true,true),List.of());
  var pending = new EntityStoresVO(new EntityVO("E2","Pending","PENDING_LICENSE",false,null,0,false,true),List.of());
  var foreign = new EntityStoresVO(new EntityVO("E3","Other","ACTIVE",true,null,0,false,false),List.of());
  var inactiveStore = mock(StoreVO.class);
  when(inactiveStore.status()).thenReturn("DISABLED");
  var existing = new EntityStoresVO(new EntityVO("E4","Existing","ACTIVE",true,null,1,false,true),List.of(inactiveStore));
  when(entities.myStores(user)).thenReturn(List.of(eligible, pending, foreign, existing));
  when(entities.myStores(merchant)).thenReturn(List.of(eligible));
  when(directory.marketOf("E1")).thenReturn("TW");
  var plan = mock(MerchantPlanService.PlanVO.class);
  when(plan.storeQuota()).thenReturn(1);
  when(plans.current("E1")).thenReturn(plan);
  assertEquals(List.of(new ai.neargo.shop.merchant.service.StoreCreationAccessService.Source(
          "E1", "Entity", null, "TW", 1, 0, true)), service.access(user).firstStoreSources());
  assertTrue(service.access(user).sources().isEmpty());
  assertTrue(service.access(merchant).firstStoreSources().isEmpty());
 }
}

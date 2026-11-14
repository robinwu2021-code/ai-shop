package ai.neargo.shop.commercebridge;

import ai.neargo.auth.store.SubjectKind;
import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.shop.auth.PermChecker;
import ai.neargo.commerce.api.mall.reporting.PurchaseReporting.Model;
import ai.neargo.shop.auth.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformMallAccessTest {
  final PermChecker perm=mock(PermChecker.class);
  final PlatformMallAccess access=new PlatformMallAccess(perm);
  void login(Realm realm,SubjectKind kind,String tenant,DataScopeSpec scope){
    var user=new LoginUser(realm,kind,"OPS1","test",List.of(),List.of(),tenant,scope);
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,"",List.of()));
  }
  @AfterEach void clear(){SecurityContextHolder.clearContext();}
  @Test void realPermissionBeanWiresAndUsesCurrentIdentity() {
    try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
      context.registerBean("perm",PermChecker.class,()->new PermChecker(context.getBeanProvider(LivePermResolver.class)));
      context.registerBean(PlatformMallAccess.class);
      context.refresh();
      login(Realm.OPERATOR,SubjectKind.OPS,"MAIN",DataScopeSpec.ALL);
      assertThrows(RuntimeException.class,()->context.getBean(PlatformMallAccess.class).require("commerce:platform:orders:read",null,false));
    }
  }
  @Test void appliesExplicitScopeAndLivePermissionIntersection() {
    login(Realm.OPERATOR,SubjectKind.OPS,"MAIN",DataScopeSpec.of("MERCHANT",Set.of("M1")));
    when(perm.can(any())).thenReturn(true);
    var result=access.require("commerce:platform:orders:read",null,false);
    assertEquals(Set.of(Model.ENTITY,Model.STORE),result.models());
    assertTrue(result.access().allows("M1"));assertFalse(result.access().allows("M2"));
    when(perm.can("merchant:merchant:read")).thenReturn(false);
    assertEquals(Set.of(Model.STORE),access.require("commerce:platform:orders:read",null,false).models());
    assertThrows(RuntimeException.class,()->access.require("commerce:platform:orders:read","ENTITY",false));
  }
  @Test void queueRequiresOriginalReviewCapabilitiesAndNewMenuNeverGrantsWrites() {
    login(Realm.OPERATOR,SubjectKind.OPS,"MAIN",DataScopeSpec.ALL);
    when(perm.can(any())).thenReturn(true);
    when(perm.can("merchant:merchant:ban")).thenReturn(false);
    assertEquals(Set.of(Model.STORE),access.require("commerce:platform:claims:read",null,true).models());
    when(perm.can("commerce:mall:offline:review")).thenReturn(false);
    assertThrows(RuntimeException.class,()->access.require("commerce:platform:claims:read",null,true));
  }
  @Test void missingScopeWrongRealmWrongTenantAndUnsupportedDimensionsFailClosed() {
    when(perm.can(any())).thenReturn(true);
    login(Realm.OPERATOR,SubjectKind.OPS,"MAIN",null);
    assertThrows(RuntimeException.class,()->access.require("commerce:platform:orders:read",null,false));
    login(Realm.CONSUMER,SubjectKind.USR,"MAIN",DataScopeSpec.ALL);
    assertThrows(RuntimeException.class,()->access.require("commerce:platform:orders:read",null,false));
    login(Realm.OPERATOR,SubjectKind.OPS,"OTHER",DataScopeSpec.ALL);
    assertThrows(RuntimeException.class,()->access.require("commerce:platform:orders:read",null,false));
    login(Realm.OPERATOR,SubjectKind.OPS,"MAIN",DataScopeSpec.of("COMMUNITY",Set.of("C1")));
    assertFalse(access.require("commerce:platform:orders:read",null,false).access().allows("M1"));
  }
}

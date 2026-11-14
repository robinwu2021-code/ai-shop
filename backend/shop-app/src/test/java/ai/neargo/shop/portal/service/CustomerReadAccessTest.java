package ai.neargo.shop.portal.service;

import ai.neargo.auth.store.SubjectKind;
import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.shop.auth.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerReadAccessTest {
  final PermChecker perm=mock(PermChecker.class);final CustomerReadAccess access=new CustomerReadAccess(perm);
  void login(Realm realm,SubjectKind kind,String tenant,DataScopeSpec scope){var user=new LoginUser(realm,kind,"OPS1","Operator",List.of(),List.of(),tenant,scope);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,"",List.of()));}
  @AfterEach void clear(){SecurityContextHolder.clearContext();}
  @Test void requiresDedicatedUserPermissionAndTrustedOperatorRealm(){
    when(perm.can("merchant:merchant:read")).thenReturn(true);login(Realm.OPERATOR,SubjectKind.OPS,"MAIN",DataScopeSpec.ALL);
    assertThrows(RuntimeException.class,()->access.require("customer:user:read"));when(perm.can("customer:user:read")).thenReturn(true);assertTrue(access.require("customer:user:read").all());
    login(Realm.MERCHANT,SubjectKind.USR,"MAIN",DataScopeSpec.ALL);assertThrows(RuntimeException.class,()->access.require("customer:user:read"));login(Realm.OPERATOR,SubjectKind.OPS,"OTHER",DataScopeSpec.ALL);assertThrows(RuntimeException.class,()->access.require("customer:user:read"));
  }
  @Test void scopesFromServerIdentityAndFailsClosedWithoutSupportedAnchors(){
    when(perm.can(anyString())).thenReturn(true);login(Realm.OPERATOR,SubjectKind.OPS,"MAIN",DataScopeSpec.of("MERCHANT",Set.of("E1")));assertEquals(Set.of("E1"),access.require("member:member:read").entities());
    login(Realm.OPERATOR,SubjectKind.OPS,"MAIN",DataScopeSpec.of("COMMUNITY",Set.of("C1")));var empty=access.require("member:member:read");assertFalse(empty.all());assertTrue(empty.entities().isEmpty());
    login(Realm.OPERATOR,SubjectKind.OPS,"MAIN",null);assertThrows(RuntimeException.class,()->access.require("member:member:read"));
  }
}

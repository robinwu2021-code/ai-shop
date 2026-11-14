package ai.neargo.shop.commercebridge;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.auth.store.*;
import ai.neargo.commerce.api.access.AccessDenied;
import ai.neargo.shop.auth.*;
import ai.neargo.shop.auth.store.DbAccountSessionVerifier;
import ai.neargo.shop.config.SessionProfiles;
import ai.neargo.shop.platform.auth.OperatorIdentityLoader;
import ai.neargo.shop.platform.mapper.PlatformMappers.StaffMapper;
import ai.neargo.shop.platform.perm.RolePermResolver;
import ai.neargo.shop.platform.perm.mapper.PermMappers.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.*;
import java.util.*;

@EnabledIfEnvironmentVariable(
        named = "COMMERCE_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:.*")
class MallAuthorityMysqlTest {
    static void authenticated(LoginUser user, String token) {
        var auth = new UsernamePasswordAuthenticationToken(user, null, List.of());
        auth.setDetails(token);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void pendingStoreProcurementUsesFreshRealAuthorityButBusinessEndRemainsClosed() throws Exception {
        try(var f=new AccessMysqlFixture()) {
            new ai.neargo.commerce.persistence.CommerceMigrationRunner(f.ds).migrate();
            var ref=new ai.neargo.commerce.api.common.StoreRef("MAIN","S");
            var subscriptions=new ai.neargo.commerce.persistence.JdbcSubscriptionRepository(f.ds,n->UUID.randomUUID().toString());
            CommerceAccessSecurityFlowTest.seedSubscriptions(f,ref,subscriptions,new org.springframework.transaction.support.TransactionTemplate(f.manager));
            var resolver=new ai.neargo.shop.merchant.service.impl.BizIdentityResolverImpl(
                f.mybatis.getMapper(ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper.class),
                org.mockito.Mockito.mock(ai.neargo.shop.spi.user.PickupQueryPort.class),
                f.mybatis.getMapper(ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper.class),
                f.mybatis.getMapper(ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper.class),
                f.mybatis.getMapper(ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreRoleMapper.class),
                f.mybatis.getMapper(ai.neargo.shop.merchant.mapper.MerchantMappers.MchRoleMapper.class));
            var sessions=new DbAccountSessionVerifier(f.dao,f.loader,f.clock,f.manager);
            var stores=new ShopCommerceStoreAdapter(f.ds,f.manager);
            var authority=new ShopMallAuthority(sessions,sessions,resolver,stores,f.clock);
            var user=LoginUser.merchantByUser("U","owner");
            String token=f.store.issue(TokenStore.SessionData.of(user));
            authenticated(user,token);
            BizContext.set(resolver.resolveStrict(user,"S"));
            f.jdbc.update("UPDATE mch_store SET status='PENDING_OPEN',operational_review='PENDING' WHERE store_no='S'");
            var buyer=authority.buyer("S","commerce:mall:purchase");
            assertTrue(authority.authorize(buyer.actor(),ref,"commerce:mall:purchase").allowed());
            var endHost=new ShopCommerceActorAdapter(sessions,resolver,stores);
            var end=new ai.neargo.commerce.core.access.EndAccessService(
                new ai.neargo.commerce.persistence.JdbcEndAccessRepository(f.ds),
                new ai.neargo.commerce.persistence.JdbcCommerceUnitOfWork(f.ds,f.manager),endHost,
                org.mockito.Mockito.mock(ai.neargo.commerce.api.port.EndContextCodecPort.class),f.clock,Duration.ofMinutes(1));
            assertEquals(ai.neargo.commerce.api.access.AccessStatus.STORE_DISABLED,end.inspect(
                new ai.neargo.commerce.api.access.IssueEndContextCommand(ShopCommerceActorAdapter.current(),ref,ref,"BIZ_APP","END_BIZ_APP","biz-app",ai.neargo.commerce.api.access.EndAccessFacade.AUDIENCE)).status());
            var gate=new ShopCommerceEndContextAdapter(stores,resolver,end,sessions,f.clock);
            var request=new org.springframework.mock.web.MockHttpServletRequest("POST","/biz/stores/S/mall/offline/quotes");
            request.addHeader("X-Store-No","S");
            assertTrue(gate.admit(user,BizContext.current(),request,new org.springframework.mock.web.MockHttpServletResponse()));
            var operation=new org.springframework.mock.web.MockHttpServletRequest("GET","/biz/stores/S/pos/access");
            operation.addHeader("X-Store-No","S");
            assertFalse(gate.admit(user,BizContext.current(),operation,new org.springframework.mock.web.MockHttpServletResponse()));
            f.jdbc.update("UPDATE mch_store SET status='SUSPENDED' WHERE store_no='S'");
            assertThrows(AccessDenied.class,()->authority.buyer("S","commerce:mall:purchase"));
        } finally {SecurityContextHolder.clearContext();BizContext.clear();}
    }

    @Test
    void typedBuyerKeysAlwaysFitPersistedBuyerColumnsIncluding64CharacterRawIdentity() {
        String raw = "u".repeat(64);
        String usr = ShopMallIdentity.buyerKey(LoginUser.merchantByUser(raw, "owner"));
        String mch = ShopMallIdentity.buyerKey(LoginUser.merchant(raw, "staff"));
        assertEquals(47, usr.length());
        assertEquals(47, mch.length());
        assertNotEquals(usr, mch);
        assertEquals(
                usr, ShopMallIdentity.buyerKey(LoginUser.merchantByUser(raw, "changed display")));
    }

    @Test
    void operatorDbSessionAndFreshRolePermissionRevocationCannotFallBackToCachedAuthority()
            throws Exception {
        try (var f = new AccessMysqlFixture()) {
            f.jdbc.update(
                    "INSERT INTO"
                        + " sys_ops_staff(staff_no,username,password,real_name,roles,created_at,updated_at)"
                        + " VALUES"
                        + " ('OPS1','operator','unused','Operator','[\"MALL_OPS\"]',NOW(),NOW())");
            f.jdbc.update(
                    "INSERT INTO sys_role(role_code,name,end_code,created_at,updated_at) VALUES"
                        + " ('MALL_OPS','Mall','OPS',NOW(),NOW())");
            f.jdbc.update(
                    "INSERT INTO"
                        + " sys_function_point(point_code,function_code,name,perm_code,created_at,updated_at)"
                        + " VALUES"
                        + " ('MALL_EDIT','MALL','edit','commerce:catalog:edit',NOW(),NOW())");
            f.jdbc.update(
                    "INSERT INTO"
                        + " sys_role_point(role_code,point_code,end_code,created_at,updated_at)"
                        + " VALUES ('MALL_OPS','MALL_EDIT','OPS',NOW(),NOW())");
            var perms =
                    new RolePermResolver(
                            f.mybatis.getMapper(RolePointMapper.class),
                            f.mybatis.getMapper(FunctionPointMapper.class),
                            f.mybatis.getMapper(RoleMapper.class),
                            60000);
            var loader = new OperatorIdentityLoader(f.mybatis.getMapper(StaffMapper.class), perms);
            IdentityLoader<LoginUser> strict =
                    new IdentityLoader<>() {
                        public Optional<LoginUser> load(String no) {
                            return loader.loadFresh(no);
                        }

                        public SubjectKind kind() {
                            return SubjectKind.OPS;
                        }
                    };
            var dao = new SessionDao(JdbcClient.create(f.ds), SessionProfiles.OPERATOR);
            String token = "otk_native_mall_session";
            dao.insert(
                    TokenHash.of(token),
                    "OPS1",
                    "OPS",
                    LocalDateTime.now(f.clock),
                    LocalDateTime.now(f.clock).plusHours(1));
            var ops =
                    new DbAccountSessionVerifier(
                            dao,
                            new CompositeIdentityLoader<>(strict),
                            f.clock,
                            f.manager,
                            Realm.OPERATOR);
            var authority =
                    new ShopMallAuthority(
                            new DbAccountSessionVerifier(f.dao, f.loader, f.clock, f.manager),
                            ops,
                            BizIdentityResolver.NONE,
                            new ShopCommerceStoreAdapter(f.ds, f.manager),
                            f.clock);
            var cached = loader.load("OPS1").orElseThrow();
            authenticated(cached, token);
            var verified = assertDoesNotThrow(() -> authority.operator("commerce:catalog:edit"));
            assertNotNull(verified);
            assertEquals("OPS1", verified.principalNo());
            assertEquals(
                    new ai.neargo.commerce.api.common.RawPrincipalIdentity(
                            "OPERATOR", "OPS", "OPS1"),
                    verified.rawIdentity());
            var forged =
                    new ai.neargo.commerce.api.common.ActorContext(
                            verified.tenantNo(),
                            verified.subjectKind(),
                            verified.principalNo(),
                            verified.accountSessionFingerprint(),
                            new ai.neargo.commerce.api.common.RawPrincipalIdentity(
                                    "OPERATOR", "OPS", "someone-else"));
            assertFalse(authority.allowed(forged, "commerce:catalog:edit"));
            new ai.neargo.commerce.persistence.CommerceMigrationRunner(f.ds).migrate();
            var catalog =
                    new ai.neargo.commerce.core.catalog.DefaultCatalogFacade(
                            new ai.neargo.commerce.persistence.JdbcCatalogRepository(f.ds),
                            new ai.neargo.commerce.persistence.JdbcCommerceUnitOfWork(
                                    f.ds, f.manager),
                            authority,
                            new ai.neargo.commerce.api.port.BusinessEndReadinessPort() {
                                public boolean verified(
                                        String tenant,
                                        ai.neargo.commerce.api.catalog.BusinessEndDraft draft) {
                                    return false;
                                }

                                public Set<String> requiredEntitlementCodes(
                                        String tenant, String type) {
                                    return Set.of();
                                }
                            },
                            new ai.neargo.commerce.persistence.JdbcCommerceEventPersistence(f.ds),
                            f.clock,
                            name -> UUID.randomUUID().toString());
            var mvc =
                    org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                                    new ai.neargo.commerce.starter.http
                                            .CommerceCatalogHttpController(
                                            catalog, authority, new ShopMallEnvelope()))
                            .setControllerAdvice(new CommerceAccessErrors())
                            .build();
            String body =
                    "{\"typeCode\":\"retail.cn:1\",\"name\":\"Retail\",\"sortOrder\":1,\"enabled\":false,\"expectedVersion\":0,\"reason\":\"native"
                        + " mvc\"}";
            assertEquals(
                    200,
                    mvc.perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.post("/ops/business-types")
                                            .contentType("application/json")
                                            .content(body))
                            .andReturn()
                            .getResponse()
                            .getStatus());
            assertEquals(
                    1,
                    f.jdbc.queryForObject(
                            "SELECT count(*) FROM sys_business_type WHERE"
                                + " business_type_code='retail.cn:1'",
                            Integer.class));
            assertEquals(
                    400,
                    mvc.perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.post("/ops/business-types")
                                            .contentType("application/json")
                                            .content(
                                                    body.substring(0, body.length() - 1)
                                                            + ",\"tenantNo\":\"evil\"}"))
                            .andReturn()
                            .getResponse()
                            .getStatus());
            f.jdbc.update("DELETE FROM sys_role_point");
            assertEquals(
                    403,
                    mvc.perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.post("/ops/business-types")
                                            .contentType("application/json")
                                            .content(body))
                            .andReturn()
                            .getResponse()
                            .getStatus());
            assertThrows(AccessDenied.class, () -> authority.operator("commerce:catalog:edit"));
            f.jdbc.update(
                    "INSERT INTO"
                        + " sys_role_point(role_code,point_code,end_code,created_at,updated_at)"
                        + " VALUES ('MALL_OPS','MALL_EDIT','OPS',NOW(),NOW())");
            assertNotNull(authority.operator("commerce:catalog:edit"));
            dao.revoke(TokenHash.of(token), RevokeReason.LOGOUT, LocalDateTime.now(f.clock));
            assertThrows(AccessDenied.class, () -> authority.operator("commerce:catalog:edit"));
            authenticated(LoginUser.merchantByUser("OPS1", "forged buyer"), token);
            assertThrows(AccessDenied.class, () -> authority.operator("commerce:catalog:edit"));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}

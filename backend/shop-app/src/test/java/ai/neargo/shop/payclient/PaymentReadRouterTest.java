package ai.neargo.shop.payclient;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.auth.*;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.acquiring.service.MerchantPaymentQueryService.*;
import ai.neargo.shop.pay.biz.PayBizFacade.PaymentQuery;
import ai.neargo.shop.pay.client.PortResult;
import ai.neargo.shop.portal.biz.pay.BizPaymentController;
import ai.neargo.shop.spi.platform.AuditLogPort;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PaymentReadRouterTest {
    final BizPaymentAppService normal = mock(BizPaymentAppService.class);
    final SandboxPaymentReadApi sandbox = mock(SandboxPaymentReadApi.class);
    final BizIdentityResolver identities = mock(BizIdentityResolver.class);
    final AuditLogPort audit = mock(AuditLogPort.class);
    final LoginUser user = LoginUser.merchant("OP1", "reader");
    JdbcTemplate jdbc;
    PaymentReadRouter router;
    final PaymentReadProperties configured = new PaymentReadProperties(
            true, "SANDBOX", true, "AI_SHOP_UME_SANDBOX", "private-test-credential");

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:payment-read-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE mch_entity(entity_no VARCHAR(64) PRIMARY KEY, market VARCHAR(8),deleted INT)");
        jdbc.execute("CREATE TABLE mch_store(store_no VARCHAR(64),entity_no VARCHAR(64),deleted INT)");
        jdbc.execute("CREATE TABLE payment_collection_source(environment VARCHAR(16),pay_app_id BIGINT,"
                + "source_type VARCHAR(16),source_id BIGINT,payment_store_id BIGINT,"
                + "store_no VARCHAR(64),entity_no VARCHAR(64),enabled BOOLEAN)");
        jdbc.update("INSERT INTO mch_entity VALUES('E1','TW',0),('E2','TW',0)");
        jdbc.update("INSERT INTO mch_store VALUES('S1','E1',0)");
        jdbc.update("INSERT INTO payment_collection_source VALUES('SANDBOX',42,'CHECKOUT',42,9001,'S1','E1',TRUE)");
        router = new PaymentReadRouter(normal, configured, sandbox, ds, identities, audit);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        BizContext.set(scope(Set.of(BizPerms.PAY_ORDER_VIEW, BizPerms.PAY_STATEMENT)));
        when(identities.resolveStrict(user,"S1")).thenReturn(BizContext.current());
    }
    static BizContext scope(Set<String> permissions) {
        return new BizContext("E1",Set.of(),Set.of(),Set.of("S1"),"S1",false,
                Map.of(),Map.of("S1",permissions));
    }
    @AfterEach void clear() {BizContext.clear();SecurityContextHolder.clearContext();}

    @Test void absentAndProductionHeadersUseOriginalServiceWithoutAnySandboxLookup() {
        jdbc.execute("DROP TABLE payment_collection_source");
        var status = new CollectStatus(false,List.of("PRODUCTION"));
        when(normal.collectStatus("S1")).thenReturn(status);
        assertThat(router.status(null)).isSameAs(status);
        assertThat(router.status("PRODUCTION")).isSameAs(status);
        verify(normal,times(2)).collectStatus("S1");
        var query = new PaymentQuery(null,null,null,null,null,null,null,null,null,20);
        for (String environment : Arrays.asList(null,"PRODUCTION")) {
            router.orders(environment,query);router.summary(environment,"2026-11-14","2026-11-14");
            router.lookup(environment,100L,"10m","123456");router.detail(environment,"ORDER1");
        }
        verify(normal,times(2)).paymentOrders("S1",query);
        verify(normal,times(2)).paymentSummary("S1","2026-11-14","2026-11-14");
        verify(normal,times(2)).paymentLookup("S1",100L,"10m","123456");
        verify(normal,times(2)).paymentDetail("S1","ORDER1");
        verifyNoInteractions(sandbox,identities);
    }
    @Test void allFiveReadIntentsUseOnlyMappedSandboxWithActualStoreAndOperator() {
        var query = new PaymentQuery(null,null,null,null,null,null,null,null,null,20);
        when(sandbox.status(any(),any(),any(),any())).thenReturn(PortResult.ok(new CollectStatus(true,List.of("SB"))));
        when(sandbox.orders(any(),any(),any(),any())).thenReturn(PortResult.ok(new PaymentPage(List.of(),"cursor")));
        when(sandbox.summary(any(),any(),any(),any(),any(),any())).thenReturn(PortResult.ok(null));
        when(sandbox.lookup(any(),any(),any(),any(),any(),any(),any())).thenReturn(PortResult.ok(new Lookup(List.of(),List.of())));
        when(sandbox.detail(any(),any(),any(),any(),any())).thenReturn(PortResult.ok(null));
        assertThat(router.status("SANDBOX").channels()).containsExactly("SB");
        assertThat(router.orders("SANDBOX",query).nextCursor()).isEqualTo("cursor");
        router.summary("SANDBOX","2026-11-14","2026-11-14");
        router.lookup("SANDBOX",100L,"10m",null);router.detail("SANDBOX","ORDER1");
        verify(sandbox).detail("S1","ORDER1","OP1","AI_SHOP_UME_SANDBOX","private-test-credential");
        verifyNoInteractions(normal);
    }
    @ParameterizedTest @ValueSource(strings={"","sandbox","LIVE","SANDBOX,PRODUCTION"," PRODUCTION"})
    void invalidHeadersNeverSelectEitherEnvironment(String header) {
        assertThatThrownBy(()->router.status(header)).isInstanceOf(BizException.class);
        verifyNoInteractions(normal,sandbox,identities);
    }
    @ParameterizedTest @ValueSource(strings={
            "UPDATE payment_collection_source SET enabled=FALSE",
            "UPDATE payment_collection_source SET source_id=43",
            "UPDATE payment_collection_source SET source_type='BIZ'",
            "UPDATE payment_collection_source SET environment='PRODUCTION'",
            "UPDATE payment_collection_source SET entity_no='E2'",
            "UPDATE mch_store SET deleted=1",
            "UPDATE mch_entity SET deleted=1 WHERE entity_no='E1'",
            "UPDATE mch_entity SET market='AE' WHERE entity_no='E1'",
            "DELETE FROM payment_collection_source"})
    void missingRevokedOrMismatchedSourceCannotAuthorizeSandbox(String mutation) {
        jdbc.update(mutation);
        assertThatThrownBy(()->router.status("SANDBOX")).isInstanceOf(BizException.class);
        verifyNoInteractions(normal,sandbox);
    }
    @Test void currentStrictIdentityPermissionAndExactEntityAreRequired() {
        when(identities.resolveStrict(user,"S1")).thenReturn(BizContext.NONE);
        assertThatThrownBy(()->router.status("SANDBOX")).isInstanceOf(BizException.class);
        when(identities.resolveStrict(user,"S1")).thenReturn(scope(Set.of()));
        assertThatThrownBy(()->router.status("SANDBOX")).isInstanceOf(BizException.class);
        when(identities.resolveStrict(user,"S1")).thenReturn(scope(Set.of(BizPerms.PAY_ORDER_VIEW)));
        assertThatThrownBy(()->router.summary("SANDBOX",null,null)).isInstanceOf(BizException.class);
        BizContext.set(BizContext.NONE);
        assertThatThrownBy(()->router.status("SANDBOX")).isInstanceOf(BizException.class);
        verifyNoInteractions(normal,sandbox);
    }
    @Test void strictResolutionCannotAuthorizeAnotherEntityOrStore() {
        for (var strict : List.of(
                new BizContext("E2",Set.of(),Set.of(),Set.of("S1"),"S1",false,
                        Map.of(),Map.of("S1",Set.of(BizPerms.PAY_ORDER_VIEW))),
                new BizContext("E1",Set.of(),Set.of(),Set.of("S2"),"S2",false,
                        Map.of(),Map.of("S2",Set.of(BizPerms.PAY_ORDER_VIEW))))) {
            when(identities.resolveStrict(user,"S1")).thenReturn(strict);
            assertThatThrownBy(()->router.status("SANDBOX")).isInstanceOf(BizException.class);
        }
        verifyNoInteractions(normal,sandbox);
    }
    @Test void disabledReadFeatureOrMissingCredentialNeverFallsBackToProduction() {
        for (var p : List.of(new PaymentReadProperties(true,"SANDBOX",false,null,null),
                new PaymentReadProperties(true,"SANDBOX",true,"SB",null),
                new PaymentReadProperties(false,"SANDBOX",true,"SB","credential"))) {
            var unavailable = new PaymentReadRouter(normal,p,sandbox,jdbc.getDataSource(),identities,audit);
            assertThatThrownBy(()->unavailable.status("SANDBOX")).isInstanceOf(BizException.class);
        }
        verifyNoInteractions(normal,sandbox);
    }
    @Test void payBusinessRejectionIsNotRetriedInProduction() {
        when(sandbox.detail(any(),any(),any(),any(),any())).thenReturn(PortResult.rejected("NOT_FOUND",List.of()));
        assertThatThrownBy(()->router.detail("SANDBOX","MISSING"))
                .isInstanceOfSatisfying(BizException.class,e->assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(normal,audit);
    }
    @Configuration(proxyBeanMethods=false) @EnableMethodSecurity(proxyTargetClass=true)
    static class SecurityConfiguration {}
    @Test void realMethodSecurityRejectsPermissionlessReadsAndHeaderDoesNotRouteWrites() throws Exception {
        try(var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("api");context.register(SecurityConfiguration.class);
            context.registerBean("perm",PermChecker.class,()->new PermChecker(context.getBeanProvider(LivePermResolver.class)));
            context.registerBean(BizPaymentController.class,()->new BizPaymentController(normal,router));context.refresh();
            var controller = context.getBean(BizPaymentController.class);
            BizContext.set(scope(Set.of()));
            assertThatThrownBy(()->controller.collectStatus("SANDBOX")).isInstanceOf(BizException.class);
            verifyNoInteractions(normal,sandbox,identities);
            BizContext.set(scope(Set.of(BizPerms.PAY_ORDER_VIEW)));
            var mvc=MockMvcBuilders.standaloneSetup(controller).build();
            mvc.perform(post("/biz/payment/orders/ORDER1/query")
                    .header(PaymentReadRouter.HEADER,"SANDBOX")).andExpect(status().isOk());
            verify(normal).paymentRefresh("S1","ORDER1");verifyNoInteractions(sandbox);
        }
    }
    @Configuration(proxyBeanMethods=false) @EnableConfigurationProperties(PaymentReadProperties.class)
    static class PropertiesConfiguration {}
    @Test void oldSseEnabledConfigurationStartsWithoutNewCredentialsButExplicitReadRequiresThem() {
        var runner=new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues("shop.payment-collections.enabled=true","shop.payment-collections.environment=SANDBOX");
        runner.run(c->{assertThat(c).hasNotFailed();assertThat(c.getBean(PaymentReadProperties.class).sandboxConfigured()).isFalse();});
        runner.withPropertyValues("shop.payment-collections.sandbox-read-enabled=true")
                .run(c->assertThat(c).hasFailed());
        assertThat(configured.toString()).doesNotContain("private-test-credential");
    }
}

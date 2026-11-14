package ai.neargo.shop.commercebridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.neargo.shop.auth.*;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import ai.neargo.shop.merchant.port.MerchantPortImpl;
import ai.neargo.shop.merchant.port.StoreOwnershipWritePortImpl;
import ai.neargo.shop.merchant.service.*;
import ai.neargo.shop.platform.IndustryService;
import ai.neargo.shop.platform.MasterDataService;
import ai.neargo.shop.platform.OpsService;
import ai.neargo.shop.platform.impl.OpsServiceImpl;
import ai.neargo.shop.platform.mapper.PlatformMappers.MerchantApplyMapper;
import ai.neargo.shop.platform.mapper.PlatformMappers.StaffMapper;
import ai.neargo.shop.spi.notify.OpsAlertPort;
import ai.neargo.shop.spi.user.StoreOwnershipWritePort;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import javax.sql.DataSource;

@EnabledIfEnvironmentVariable(
        named = "COMMERCE_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:.*")
class BizFoundationAccessMysqlTest {
    @Test
    void paymentAdmissionBindsBodyQueryAndPathBeforeDispatchWithoutAnEndProof() throws Exception {
        try (var f = new AccessMysqlFixture();
                var web = new AnnotationConfigWebApplicationContext()) {
            var mvc = CommerceAccessSecurityFlowTest.merchantMvc(f, web, true);
            String bearer =
                    "Bearer "
                            + f.store.issue(
                                    TokenStore.SessionData.of(
                                            LoginUser.merchantByUser("U", "owner")));
            var service = web.getBean(MerchantPaymentService.class);
            var read =
                    get("/biz/merchant/pay-channel")
                            .header("Authorization", bearer)
                            .header("X-Store-No", "S");
            mvc.perform(read).andExpect(status().isOk());
            verify(service).availableChannels("M");
            clearInvocations(service);
            f.jdbc.update(
                    "insert into mch_store(entity_no,store_no,created_at,updated_at) values"
                            + " ('M','sibling',now(),now())");
            for (var request :
                    java.util.List.of(
                            post("/biz/merchant/payment")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"storeNo\":\"sibling\"}"),
                            post("/biz/merchant/payment")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"entityNo\":\"other\"}"),
                            post("/biz/merchant/payment/store/sibling"),
                            post("/biz/merchant/payment/store/S")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"storeNo\":\"sibling\"}"),
                            post("/biz/merchant/payment/WECHAT/refresh")
                                    .param("storeNo", "sibling"),
                            post("/biz/merchant/payment/WECHAT/refresh")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"storeNo\":\"sibling\"}"),
                            get("/biz/merchant/pay-channel").param("entityNo", "other"))) {
                mvc.perform(request.header("Authorization", bearer).header("X-Store-No", "S"))
                        .andExpect(status().isForbidden());
            }
            verifyNoInteractions(service);
            mvc.perform(
                            post("/biz/merchant/payment")
                                    .param("storeNo", "S")
                                    .header("Authorization", bearer)
                                    .header("X-Store-No", "S")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"payChannel\":\"WECHAT\"}"))
                    .andExpect(status().isOk());
            verify(service).submit(eq("M"), argThat(cmd -> "S".equals(cmd.storeNo())));
            mvc.perform(read).andExpect(status().isOk());
            f.jdbc.update("update mch_session set revoked_at=now()");
            mvc.perform(read).andExpect(status().isUnauthorized());
            verify(service, times(1)).availableChannels("M");
        }
    }

    @Test
    void logoutInvalidatesEndProofWithoutRevokingSecondSession() throws Exception {
        try (var f = new AccessMysqlFixture();
                var web = new AnnotationConfigWebApplicationContext()) {
            new ai.neargo.commerce.persistence.CommerceMigrationRunner(f.ds).migrate();
            CommerceAccessSecurityFlowTest.seedSubscriptions(
                    f,
                    new ai.neargo.commerce.api.common.StoreRef("MAIN", "S"),
                    new ai.neargo.commerce.persistence.JdbcSubscriptionRepository(
                            f.ds, ns -> java.util.UUID.randomUUID().toString()),
                    new org.springframework.transaction.support.TransactionTemplate(f.manager));
            var mvc = CommerceAccessSecurityFlowTest.merchantMvc(f, web, true);
            var account = TokenStore.SessionData.of(LoginUser.merchantByUser("U", "owner"));
            String token = f.store.issue(account);
            String other = f.store.issue(account);
            String bearer = "Bearer " + token;
            var result =
                    mvc.perform(
                                    post("/biz/stores/S/end-sessions")
                                            .header("Authorization", bearer)
                                            .header("X-Store-No", "S")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"endCode\":\"BIZ_APP\",\"clientId\":\"biz-app\"}"))
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse();
            String proof =
                    new tools.jackson.databind.ObjectMapper()
                            .readTree(result.getContentAsString())
                            .path("value")
                            .asText();
            assertFalse(proof.isBlank());
            var protectedRead =
                    get("/biz/store/S/pay-setting")
                            .header("Authorization", bearer)
                            .header("X-Store-No", "S")
                            .header("X-Client-Id", "biz-app")
                            .header("X-End-Context", proof);
            mvc.perform(protectedRead).andExpect(status().isOk());
            mvc.perform(
                            post("/biz/auth/logout")
                                    .header("Authorization", bearer)
                                    .header("X-Store-No", "foreign"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(""));
            assertTrue(f.store.get(token).isEmpty());
            assertTrue(f.store.get(other).isPresent());
            mvc.perform(protectedRead).andExpect(status().isUnauthorized());
            mvc.perform(
                            get("/biz/store/S/pay-setting")
                                    .header("Authorization", "Bearer " + other)
                                    .header("X-Store-No", "S")
                                    .header("X-Client-Id", "biz-app")
                                    .header("X-End-Context", proof))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.msg").value("CONTEXT_REFRESH_REQUIRED"));
        }
    }

    @Test
    void financeStaffUsesOnlySelectedStoreAndQualificationWritesRequireOwnEntity()
            throws Exception {
        try (var f = new AccessMysqlFixture();
                var web = new AnnotationConfigWebApplicationContext()) {
            var quals =
                    f.mybatis.getMapper(
                            ai.neargo.shop.merchant.mapper.MerchantMappers.QualificationMapper
                                    .class);
            var govern =
                    new ai.neargo.shop.merchant.service.impl.MerchantGovernServiceImpl(
                            null, null, null, null, null, null, null, null, quals, null, null, null,
                            null, null, null, null, null, null, entityNo -> {});
            var mvc =
                    CommerceAccessSecurityFlowTest.merchantMvc(
                            f, web, true, java.util.Map.of(MerchantGovernService.class, govern));
            f.jdbc.update(
                    "insert into mch_store(entity_no,store_no,created_at,updated_at) values"
                            + " ('M','L',now(),now())");
            f.jdbc.update(
                    "insert into"
                        + " mch_account(mch_account_no,entity_no,display_name,created_at,updated_at)"
                        + " values ('F','M','finance',now(),now())");
            f.jdbc.update(
                    "insert into mch_store_role(mch_account_no,store_no,role,created_at,updated_at)"
                            + " values ('F','S','FIN',now(),now())");
            f.jdbc.update(
                    "insert into mch_role(entity_no,role_code,name,perms,created_at,updated_at)"
                            + " values ('M','FIN','finance','[\"biz:finance\"]',now(),now())");
            String finance =
                    "Bearer "
                            + f.store.issue(
                                    TokenStore.SessionData.of(LoginUser.merchant("F", "finance")));
            var payments = web.getBean(MerchantPaymentService.class);
            when(payments.list("M")).thenReturn(java.util.List.of(row(""), row("S"), row("L")));
            mvc.perform(
                            get("/biz/merchant/payment")
                                    .header("Authorization", finance)
                                    .header("X-Store-No", "S"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].storeNo").value(""))
                    .andExpect(jsonPath("$[1].storeNo").value("S"));
            mvc.perform(
                            get("/biz/qualifications")
                                    .header("Authorization", finance)
                                    .header("X-Store-No", "S"))
                    .andExpect(status().isForbidden());
            f.jdbc.update("update mch_role set perms='[\"biz:store\"]' where role_code='FIN'");
            mvc.perform(
                            get("/biz/merchant/payment")
                                    .header("Authorization", finance)
                                    .header("X-Store-No", "S"))
                    .andExpect(status().isForbidden());
            verify(payments, times(1)).list("M");
            f.jdbc.update(
                    "insert into"
                        + " mch_qualification(qual_no,entity_no,qual_type,qual_name,created_at,updated_at)"
                        + " values ('Q','FOREIGN','OTHER','original',now(),now())");
            String owner =
                    "Bearer "
                            + f.store.issue(
                                    TokenStore.SessionData.of(
                                            LoginUser.merchantByUser("U", "owner")));
            for (var principal : java.util.List.of(owner, finance)) {
                mvc.perform(
                                post("/biz/qualifications/save")
                                        .header("Authorization", principal)
                                        .header("X-Store-No", "S")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"qualNo\":\"Q\",\"qualName\":\"changed\",\"qualType\":\"OTHER\",\"entityNo\":\"M\"}"))
                        .andExpect(
                                jsonPath("$.code")
                                        .value(ai.neargo.shop.common.ErrorCode.NOT_FOUND.code()));
            }
            assertEquals(
                    "original",
                    f.jdbc.queryForObject(
                            "select qual_name from mch_qualification where qual_no='Q'",
                            String.class));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void legacyFinanceChecksTheActualStoreBeforeWritingOrCallingTheChannel(boolean enabled)
            throws Exception {
        try (var f = new AccessMysqlFixture();
                var web = new AnnotationConfigWebApplicationContext()) {
            f.jdbc.update(
                    "update mch_store set commerce_model='LEGACY',is_default=1 where store_no='S'");
            f.jdbc.update(
                    "insert into mch_store(entity_no,store_no,created_at,updated_at) values"
                        + " ('M','B',now(),now())");
            f.jdbc.update(
                    "insert into mch_account(mch_account_no,entity_no,created_at,updated_at) values"
                        + " ('F','M',now(),now())");
            f.jdbc.update(
                    "insert into mch_store_role(mch_account_no,store_no,role,created_at,updated_at)"
                        + " values ('F','S','FIN',now(),now())");
            f.jdbc.update(
                    "insert into mch_role(entity_no,role_code,name,perms,created_at,updated_at)"
                        + " values ('M','FIN','finance','[\"biz:finance\"]',now(),now())");
            var gateway = mock(ai.neargo.shop.spi.pay.PayApplymentGateway.class);
            when(gateway.payChannel()).thenReturn("WECHAT");
            when(gateway.submit(any())).thenReturn("apply-S");
            when(gateway.query(any()))
                    .thenReturn(
                            new ai.neargo.shop.spi.pay.PayApplymentGateway.ApplymentResult(
                                    "APPLYING", null, null));
            var service =
                    new ai.neargo.shop.merchant.service.impl.MerchantPaymentServiceImpl(
                            f.mybatis.getMapper(
                                    ai.neargo.shop.merchant.mapper.MerchantMappers.MchPaymentMapper
                                            .class),
                            f.mybatis.getMapper(MchEntityMapper.class),
                            mock(ai.neargo.shop.spi.platform.MasterDataPort.class),
                            mock(ai.neargo.shop.spi.pay.PayChannelMasterPort.class),
                            new tools.jackson.databind.ObjectMapper(),
                            f.mybatis.getMapper(MchStoreMapper.class),
                            java.util.List.of(gateway),
                            java.util.List.of(),
                            entityNo -> {});
            var mvc =
                    CommerceAccessSecurityFlowTest.merchantMvc(
                            f,
                            web,
                            enabled,
                            java.util.Map.of(MerchantPaymentService.class, service));
            String bearer =
                    "Bearer "
                            + f.store.issue(
                                    TokenStore.SessionData.of(LoginUser.merchant("F", "finance")));
            String insert =
                    "insert into"
                        + " mch_payment_merchant(entity_no,store_no,pay_channel,channel_apply_no,created_at,updated_at)"
                        + " values ('M',?,'WECHAT',?,now(),now())";
            f.jdbc.update(insert, "", null);
            for (String selection : java.util.List.of("", "S", "B")) {
                var req =
                        post("/biz/merchant/payment/store/B")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"payChannel\":\"WECHAT\"}");
                if (!selection.isEmpty()) req.header("X-Store-No", selection);
                var opened = mvc.perform(req.header("Authorization", bearer));
                assertEquals(
                        0,
                        f.jdbc.queryForObject(
                                "select count(*) from mch_payment_merchant where store_no='B'",
                                Integer.class),
                        "B must not receive an independent payment row");
                opened.andExpect(status().isForbidden());
            }
            f.jdbc.update(insert, "B", "foreign");
            // Even with membership at B, a finance role at S cannot authorize a B operation.
            f.jdbc.update(
                    "insert into mch_store_role(mch_account_no,store_no,role,created_at,updated_at)"
                        + " values ('F','B','VIEWER',now(),now())");

            String payload =
                    "{\"payChannel\":\"WECHAT\",\"settleAccountType\":\"PERSONAL_BANK_CARD\",\"settleAccount\":\"test9999\",\"storeNo\":\"%s\"}";
            for (String selection : java.util.List.of("", "S")) {
                for (var req :
                        java.util.List.of(
                                post("/biz/merchant/payment")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(payload.formatted("B")),
                                post("/biz/merchant/payment/WECHAT/refresh").param("storeNo", "B"),
                                post("/biz/merchant/payment/WECHAT/refresh")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"storeNo\":\"B\"}"),
                                post("/biz/merchant/payment/store/S").param("storeNo", "S", "B"))) {
                    if (!selection.isEmpty()) req.header("X-Store-No", selection);
                    mvc.perform(req.header("Authorization", bearer))
                            .andExpect(status().isForbidden());
                }
                var foreign = get("/biz/merchant/payment").param("entityNo", "foreign")
                        .header("Authorization", bearer);
                if (!selection.isEmpty()) foreign.header("X-Store-No", selection);
                // The enabled selection filter rejects first. Legacy admission keeps the original
                // host ownership error envelope for an explicit foreign entity.
                var denial = mvc.perform(foreign);
                if (enabled && !selection.isEmpty()) {
                    denial.andExpect(status().isForbidden()).andExpect(jsonPath("$.code")
                            .value(ai.neargo.shop.common.ErrorCode.BIZ_ROLE_FORBIDDEN.code()));
                } else {
                    denial.andExpect(status().isOk()).andExpect(jsonPath("$.code")
                            .value(ai.neargo.shop.common.ErrorCode.FORBIDDEN.code()));
                }
                denial.andExpect(jsonPath("$.data[0]").doesNotExist());
                var read = get("/biz/merchant/payment").header("Authorization", bearer);
                if (!selection.isEmpty()) read.header("X-Store-No", selection);
                mvc.perform(read)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.length()").value(1))
                        .andExpect(jsonPath("$[0].storeNo").doesNotExist());
            }
            verify(gateway, never()).submit(any());
            verify(gateway, never()).query(any());
            assertEquals(
                    "foreign",
                    f.jdbc.queryForObject(
                            "select channel_apply_no from mch_payment_merchant where store_no='B'",
                            String.class));
            mvc.perform(
                            post("/biz/merchant/payment/store/S")
                                    .header("Authorization", bearer)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"payChannel\":\"WECHAT\"}"))
                    .andExpect(status().isOk());
            mvc.perform(
                            post("/biz/merchant/payment")
                                    .header("Authorization", bearer)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payload.formatted("S")))
                    .andExpect(status().isOk());
            verify(gateway).submit(any());
            verify(gateway).query("apply-S");
            mvc.perform(get("/biz/merchant/payment").header("Authorization", bearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[?(@.storeNo == 'S')]").exists())
                    .andExpect(jsonPath("$[?(@.storeNo == 'B')]").doesNotExist());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void agreementUsesCurrentEntityAndPreservesFirstPersonalAcceptance(boolean enabled)
            throws Exception {
        try (var f = new AccessMysqlFixture();
                var web = new AnnotationConfigWebApplicationContext();
                var services = new AnnotationConfigApplicationContext()) {
            f.jdbc.update(
                    "update mch_store set commerce_model='LEGACY',is_default=1 where store_no='S'");
            f.jdbc.update(
                    "insert into mch_entity(entity_no,name,created_at,updated_at) values"
                        + " ('B','other',now(),now())");
            f.jdbc.update(
                    "insert into"
                        + " mch_account(mch_account_no,user_no,entity_no,is_owner,created_at,updated_at)"
                        + " values ('MB','U','B',1,now(),now())");
            f.jdbc.update(
                    "insert into mch_store(entity_no,store_no,created_at,updated_at) values"
                        + " ('B','SB',now(),now())");
            f.jdbc.update(
                    "insert into"
                        + " mch_entity_apply(apply_no,user_no,entity_no,name,submitted_by,created_at,updated_at)"
                        + " values ('earlier','U','M','earlier','OPS',now(),now())");
            f.jdbc.update(
                    "insert into"
                        + " mch_entity_apply(apply_no,user_no,entity_no,name,submitted_by,created_at,updated_at)"
                        + " values ('latest','U','B','other','OPS',now(),now())");
            registerApplication(f, services, null, mock(OpsAlertPort.class));
            services.refresh();
            var mvc =
                    CommerceAccessSecurityFlowTest.merchantMvc(
                            f,
                            web,
                            enabled,
                            java.util.Map.of(OpsService.class, services.getBean(OpsService.class)));
            String bearer =
                    "Bearer "
                            + f.store.issue(
                                    TokenStore.SessionData.of(
                                            LoginUser.merchantByUser("U", "owner")));
            for (String selection : java.util.List.of("S", "")) {
                var req = post("/biz/merchant/agreement/accept").header("Authorization", bearer);
                if (!selection.isEmpty()) req.header("X-Store-No", selection);
                var confirmation = mvc.perform(req);
                assertNull(
                        f.jdbc.queryForObject(
                                "select agreed_at from mch_entity_apply where apply_no='latest'",
                                Long.class),
                        "selecting A must not consent for B");
                assertNull(
                        f.jdbc.queryForObject(
                                "select agreed_at from mch_entity_apply where apply_no='earlier'",
                                Long.class));
                confirmation.andExpect(
                        jsonPath("$.code").value(ai.neargo.shop.common.ErrorCode.FORBIDDEN.code()));
            }
            var accept =
                    post("/biz/merchant/agreement/accept")
                            .header("Authorization", bearer)
                            .header("X-Store-No", "SB");
            String time = mvc.perform(accept).andReturn().getResponse().getContentAsString();
            assertTrue(Long.parseLong(time) > 0);
            mvc.perform(accept).andExpect(content().string(time));
            assertEquals(
                    1,
                    f.jdbc.queryForObject(
                            "select count(*) from sys_audit_log where target='latest'",
                            Integer.class));
            // A USR session survives losing merchant membership, but a bound application cannot
            // become a personal first application through that loss of scope.
            f.jdbc.update("update mch_account set status='DISABLED' where user_no='U'");
            f.jdbc.update("insert into mch_entity_apply(apply_no,user_no,entity_no,name,submitted_by,created_at,updated_at)"
                    + " values ('revoked','U','B','revoked','OPS',now(),now())");
            var revokedProfile = mvc.perform(get("/biz/merchant/profile").header("Authorization", bearer));
            var revokedAccept = mvc.perform(post("/biz/merchant/agreement/accept").header("Authorization", bearer));
            assertAll(
                    () -> revokedProfile.andExpect(jsonPath("$.agreementPending").value(false)),
                    () -> revokedAccept.andExpect(jsonPath("$.code").value(ai.neargo.shop.common.ErrorCode.FORBIDDEN.code())),
                    () -> assertNull(f.jdbc.queryForObject("select agreed_at from mch_entity_apply where apply_no='revoked'", Long.class)));
            f.jdbc.update(
                    "insert into usr_account(user_no,created_at,updated_at) values"
                        + " ('new',now(),now())");
            f.jdbc.update(
                    "insert into"
                        + " mch_entity_apply(apply_no,user_no,name,submitted_by,created_at,updated_at)"
                        + " values ('first','new','first','OPS',now(),now())");
            String first =
                    "Bearer "
                            + f.store.issue(
                                    TokenStore.SessionData.of(
                                            LoginUser.merchantByUser("new", "new")));
            var firstProfile = get("/biz/merchant/profile").header("Authorization", first);
            mvc.perform(firstProfile).andExpect(jsonPath("$.agreementPending").value(true));
            mvc.perform(post("/biz/merchant/agreement/accept").header("Authorization", first))
                    .andExpect(status().isOk());
            mvc.perform(firstProfile).andExpect(jsonPath("$.agreementPending").value(false));
            assertNotNull(
                    f.jdbc.queryForObject(
                            "select agreed_at from mch_entity_apply where apply_no='first'",
                            Long.class));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class ApplyTransactions {}

    @Test
    void applicationUsesOnlySelectedOrCurrentlyOwnedLegacyShell() throws Exception {
        try (var f = new AccessMysqlFixture();
                var web = new AnnotationConfigWebApplicationContext();
                var services = new AnnotationConfigApplicationContext()) {
            var port =
                    new MerchantPortImpl(
                            null,
                            f.mybatis.getMapper(MchEntityMapper.class),
                            null,
                            null,
                            null,
                            null,
                            null,
                            f.mybatis.getMapper(MchAccountMapper.class),
                            f.mybatis.getMapper(MchStoreMapper.class),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            (ai.neargo.shop.merchant.service.PayMerchantMirror) entityNo -> {},
                            new org.springframework.beans.factory.support.StaticListableBeanFactory().getBeanProvider(ai.neargo.shop.spi.pay.PayMerchantReadSwitch.class),
                            new org.springframework.beans.factory.support.StaticListableBeanFactory().getBeanProvider(ai.neargo.shop.spi.pay.PayMerchantQueryPort.class));
            var json = new tools.jackson.databind.ObjectMapper();
            var alerts = mock(OpsAlertPort.class);
            registerApplication(f, services, port, alerts);
            services.refresh();
            var mvc =
                    CommerceAccessSecurityFlowTest.merchantMvc(
                            f,
                            web,
                            true,
                            java.util.Map.of(OpsService.class, services.getBean(OpsService.class)));
            String bearer =
                    "Bearer "
                            + f.store.issue(
                                    TokenStore.SessionData.of(
                                            LoginUser.merchantByUser("U", "owner")));
            f.jdbc.update(
                    "update mch_store set commerce_model='LEGACY',is_default=1 where store_no='S'");
            f.jdbc.update(
                    "insert into mch_entity(entity_no,name,status,created_at,updated_at) values"
                            + " ('B','Pending','PENDING_LICENSE',now(),now())");
            f.jdbc.update(
                    "insert into"
                        + " mch_account(mch_account_no,user_no,entity_no,is_owner,status,created_at,updated_at)"
                        + " values ('AB','U','B',1,'DISABLED',now(),now())");
            f.jdbc.update(
                    "insert into"
                        + " mch_store(entity_no,store_no,is_default,commerce_model,business_type_code,created_at,updated_at)"
                        + " values ('B','SB',1,'STORE_V1','RETAIL',now(),now())");
            for (String header : java.util.List.of("S", "")) {
                var response = submitApplication(mvc, bearer, header);
                assertEquals(
                        ai.neargo.shop.common.ErrorCode.FORBIDDEN.code(),
                        json.readTree(response.getContentAsString()).path("code").asInt(),
                        header);
                assertEquals(
                        0,
                        f.jdbc.queryForObject(
                                "select count(*) from mch_entity_apply", Integer.class));
                verifyNoInteractions(alerts);
            }
            f.jdbc.update("update mch_store set commerce_model='LEGACY' where store_no='SB'");
            f.jdbc.update("update mch_account set status='ACTIVE' where mch_account_no='AB'");
            var rejected = submitApplication(mvc, bearer, "S");
            assertEquals(
                    ai.neargo.shop.common.ErrorCode.FORBIDDEN.code(),
                    json.readTree(rejected.getContentAsString()).path("code").asInt());
            assertEquals(
                    0,
                    f.jdbc.queryForObject("select count(*) from mch_entity_apply", Integer.class));
            verifyNoInteractions(alerts);
            var accepted = submitApplication(mvc, bearer, "SB");
            // This focused MVC fixture returns the controller VO directly on success.
            assertEquals(200, accepted.getStatus());
            assertEquals(
                    "B",
                    f.jdbc.queryForObject(
                            "select entity_no from mch_entity_apply where user_no='U'",
                            String.class));
        }
    }

    static void registerApplication(
            AccessMysqlFixture f,
            AnnotationConfigApplicationContext services,
            ai.neargo.shop.spi.user.MerchantAdminPort port,
            OpsAlertPort alerts) {
        var json = new tools.jackson.databind.ObjectMapper();

        // Use real transactions + actual application/membership/store rows. The dictionary,
        // operator recipients and notification hook are independent of target selection.
        services.register(ApplyTransactions.class,
                ai.neargo.shop.platform.port.MarketContactPolicyPortImpl.class);
        services.registerBean("dataSource", DataSource.class, () -> f.ds);
        services.registerBean(StoreOwnershipWritePort.class, () -> new StoreOwnershipWritePortImpl(f.jdbc));
        services.registerBean(ai.neargo.shop.platform.MarketPhonePolicyService.class,
                () -> mock(ai.neargo.shop.platform.MarketPhonePolicyService.class));
        if (services.getBeanFactory().getBeanNamesForType(
                ai.neargo.shop.spi.user.MerchantQueryPort.class, false, false).length == 0) {
            services.registerBean(ai.neargo.shop.spi.user.MerchantQueryPort.class,
                    () -> port instanceof ai.neargo.shop.spi.user.MerchantQueryPort queries
                            ? queries : mock(ai.neargo.shop.spi.user.MerchantQueryPort.class));
        }
        services.registerBean(DataSourceTransactionManager.class, () -> f.manager);
        services.registerBean(
                OpsServiceImpl.class,
                () ->
                        new OpsServiceImpl(
                                mock(StaffMapper.class),
                                null,
                                null,
                                null,
                                f.mybatis.getMapper(
                                        ai.neargo.shop.platform.mapper.PlatformMappers
                                                .AuditLogMapper.class),
                                f.mybatis.getMapper(MerchantApplyMapper.class),
                                port,
                                null,
                                null,
                                json,
                                json,
                                registeredIndustries(),
                                knownSubjects(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                "mail",
                                alerts,
                                "",
                                null,
                                null));
    }

    private static MockHttpServletResponse submitApplication(
            MockMvc mvc, String bearer, String store) throws Exception {
        var request =
                post("/biz/merchant/apply")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"name\":\"License"
                                    + " shop\",\"subject\":\"PERSONAL\",\"contactName\":\"Owner\",\"contactPhone\":\"15965010109\"}");
        if (!store.isBlank()) request.header("X-Store-No", store);
        return mvc.perform(request).andReturn().getResponse();
    }

    private ai.neargo.shop.merchant.dto.PaymentApplymentVO row(String store) {
        return new ai.neargo.shop.merchant.dto.PaymentApplymentVO(
                "WECHAT",
                "test",
                "NONE",
                false,
                null,
                null,
                null,
                null,
                null,
                java.util.List.of(),
                false,
                null,
                null,
                store);
    }

    /**
     * 主体门禁对查不到的主体、未登记的行业 fail-closed：替身须认得共用本装配的测试所提交的值
     * （本类 PERSONAL → NATURAL_PERSON；FoundationQueryMysqlTest 的 ENTERPRISE + RETAIL）。
     */
    private static MasterDataService knownSubjects() {
        MasterDataService master = mock(MasterDataService.class);
        org.mockito.Mockito.when(master.canonicalSubject("PERSONAL")).thenReturn("NATURAL_PERSON");
        org.mockito.Mockito.when(master.canonicalSubject("ENTERPRISE")).thenReturn("ENTERPRISE");
        org.mockito.Mockito.when(master.enabledSubjects()).thenReturn(java.util.List.of("NATURAL_PERSON", "ENTERPRISE"));
        return master;
    }

    private static IndustryService registeredIndustries() {
        IndustryService industries = mock(IndustryService.class);
        org.mockito.Mockito.when(industries.registered("RETAIL")).thenReturn(true);
        return industries;
    }
}

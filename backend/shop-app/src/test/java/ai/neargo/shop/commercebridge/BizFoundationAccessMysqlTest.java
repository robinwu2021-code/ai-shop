package ai.neargo.shop.commercebridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.neargo.shop.auth.*;
import ai.neargo.shop.merchant.service.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.MediaType;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

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
                    .andExpect(status().isOk());
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
                            null, null, null, null, null, null);
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
}

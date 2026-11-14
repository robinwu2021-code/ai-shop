package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.policy.CheckoutAccessPolicy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

@SpringBootTest(
        properties =
                "spring.datasource.url=${CHECKOUT_NATIVE_MYSQL_URL:jdbc:h2:mem:checkout-owner;"
                        + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}")
@ActiveProfiles("test")
class CheckoutAuthenticationFlowTest {
    @Autowired private CheckoutAccessPolicy policy;
    @Autowired private TokenStores stores;
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void consumerSessionRequiredBeyondAConstructedPrincipal() {
        install(LoginUser.consumer("invented-user", "Nobody"), null);
        assertThatThrownBy(policy::requireCurrentBuyer).isInstanceOf(RuntimeException.class);
    }

    @Test
    void merchantDelegationCannotImpersonateBuyer() {
        install(
                LoginUser.consumer("merchant-user", "Merchant").withRealm(Realm.MERCHANT),
                "btk_untrusted");
        assertThatThrownBy(policy::requireCurrentBuyer).isInstanceOf(RuntimeException.class);
    }

    @Test
    void revokedTokenCannotAuthorizeCheckoutEvenWithAnOldPrincipal() throws Exception {
        Session session = session();
        stores.of(Realm.CONSUMER).revoke(session.token());
        install(session.user(), session.token());
        assertThatThrownBy(policy::requireCurrentBuyer).isInstanceOf(RuntimeException.class);
    }

    @Test
    void sessionCannotBeAttachedToADifferentConsumer() throws Exception {
        Session session = session();
        install(LoginUser.consumer("other-user", "Other"), session.token());
        assertThatThrownBy(policy::requireCurrentBuyer).isInstanceOf(RuntimeException.class);
    }

    @Test
    void currentRealConsumerSessionCanAuthorizeCheckout() throws Exception {
        Session session = session();
        install(session.user(), session.token());
        assertThat(policy.requireCurrentBuyer()).isEqualTo(session.user().userNo());
    }

    private Session session() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String token = TestLogin.consumerByWechat(mvc, json, "checkout-" + UUID.randomUUID());
        String body =
                mvc.perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(json.readTree(body).get("code").asInt()).isZero();
        LoginUser user = stores.of(Realm.CONSUMER).get(token).orElseThrow().user();
        return new Session(user, token);
    }

    private static void install(LoginUser user, String token) {
        var authentication =
                UsernamePasswordAuthenticationToken.authenticated(user, null, List.of());
        if (token != null) {
            authentication.setDetails(token);
        }
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private record Session(LoginUser user, String token) {}
}

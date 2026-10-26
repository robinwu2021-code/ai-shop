package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.auth.store.SubjectKind;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.foodclient.FoodScopeSigningInterceptor;
import ai.neargo.shop.food.scope.FoodScopeCredentials;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** TDD-餐饮系统-内部调用签名凭证 AC5 / AC7（宿主侧）与「宿主签的、food-server 验得过」。 */
class FoodScopeSigningInterceptorTest {
    private static final String KEY = "food-scope-test-key-0123456789abcdef";
    private static final long NOW = 1_800_000_000L;
    private static final String PATH = "/internal/food/carts/get";

    @AfterEach
    void clear() {
        BizContext.clear();
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private static void webRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    private static void login(Realm realm, SubjectKind kind, String userNo) {
        var user = new LoginUser(realm, kind, userNo, "n", List.of(), List.of(), "MAIN", null);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
    }

    private static void staff(String merchant, Set<String> stores, String user) {
        webRequest();
        login(Realm.MERCHANT, SubjectKind.MCH, user);
        BizContext.set(new BizContext(merchant, Set.of(), Set.of(), stores, stores.iterator().next(), false,
                Map.of(), Map.of()));
    }

    /** 走一遍拦截器，返回发出去的凭证头；拦截器拒签时抛出。 */
    private static String send(String key, String body) throws Exception {
        var request = new MockClientHttpRequest(HttpMethod.POST, URI.create("http://food.svc" + PATH));
        AtomicReference<String> header = new AtomicReference<>();
        ClientHttpRequestExecution execution = (r, b) -> {
            header.set(r.getHeaders().getFirst(FoodScopeCredentials.HEADER));
            return new MockClientHttpResponse(new byte[0], 200);
        };
        new FoodScopeSigningInterceptor(FoodScopeCredentials.key(key), () -> NOW)
                .intercept(request, body.getBytes(StandardCharsets.UTF_8), execution);
        return header.get();
    }

    /** 按 food-server 的方式验：同一把钥匙、按方法 + 路径 + 请求体重算摘要。 */
    private static FoodScopeCredentials.Claims verify(String header, String body) {
        return FoodScopeCredentials.verify(header, List.of(FoodScopeCredentials.key(KEY)),
                FoodScopeCredentials.digest("POST", PATH, body.getBytes(StandardCharsets.UTF_8)), NOW);
    }

    @Test
    void staffRequestInsideTheContextIsSignedAndVerifies() throws Exception {
        staff("E", Set.of("A", "B"), "U");
        String body = "{\"entityNo\":\"E\",\"storeNo\":\"A\",\"actorNo\":\"U\"}";
        var claims = verify(send(KEY, body), body);
        assertThat(claims.realm()).isEqualTo(FoodScopeCredentials.Realm.STAFF);
        assertThat(claims.entity()).isEqualTo("E");
        assertThat(claims.stores()).containsExactly("A");
        assertThat(claims.actor()).isEqualTo("U");
    }

    @Test
    void ac5HostRefusesToSignAStoreEntityOrActorOutsideTheContext() {
        staff("E", Set.of("A"), "U");
        for (String body : List.of(
                "{\"entityNo\":\"E\",\"storeNo\":\"B\",\"actorNo\":\"U\"}",
                "{\"entityNo\":\"OTHER\",\"storeNo\":\"A\",\"actorNo\":\"U\"}",
                "{\"entityNo\":\"E\",\"storeNo\":\"A\",\"actorNo\":\"SOMEONE\"}")) {
            assertThatThrownBy(() -> send(KEY, body)).isInstanceOfSatisfying(BizException.class,
                    e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        }
    }

    @Test
    void consumerBuyerMustBeTheLoggedInConsumer() throws Exception {
        webRequest();
        login(Realm.CONSUMER, SubjectKind.USR, "B1");
        String own = "{\"access\":{\"sessionNo\":\"G\",\"buyerNo\":\"B1\"}}";
        assertThat(verify(send(KEY, own), own).realm()).isEqualTo(FoodScopeCredentials.Realm.CONSUMER);
        assertThatThrownBy(() -> send(KEY, "{\"access\":{\"sessionNo\":\"G\",\"buyerNo\":\"B2\"}}"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void backgroundCallWithoutRequestIsSystem() throws Exception {
        String body = "{\"task\":\"QUEUE_NOTICE\",\"limit\":100}";
        assertThat(verify(send(KEY, body), body).realm()).isEqualTo(FoodScopeCredentials.Realm.SYSTEM);
    }

    @Test
    void ac7MissingKeyFailsClosedOnTheHost() {
        assertThatThrownBy(() -> send("", "{}")).isInstanceOfSatisfying(ServiceCallException.class,
                e -> assertThat(e.outcome()).isEqualTo(CallOutcome.NOT_CONFIGURED));
    }
}

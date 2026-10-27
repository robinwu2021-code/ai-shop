package ai.neargo.shop.food.scope;

import ai.neargo.shop.food.scope.FoodScopeCredentials.Claims;
import ai.neargo.shop.food.scope.FoodScopeCredentials.Realm;
import java.util.List;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 测试里给 MockMvc 请求签范围凭证：在请求最终成形之后（路径、查询串、请求体都定了）才算摘要，
 * 与宿主拦截器签发的方式一致。
 */
public final class FoodScopeTestSigning {
    public static final String KEY = "food-scope-test-key-0123456789abcdef";
    public static final String KEY_PROPERTY = "shop.food.scope-key=" + KEY;

    private FoodScopeTestSigning() {}

    /** 后台任务身份：不约束请求体，传输测试默认用它。 */
    public static RequestPostProcessor system() {
        return sign(Realm.SYSTEM, null, List.of(), null, KEY);
    }

    public static RequestPostProcessor staff(String entity, List<String> stores, String actor) {
        return sign(Realm.STAFF, entity, stores, actor, KEY);
    }

    public static RequestPostProcessor consumer(String actor) {
        return sign(Realm.CONSUMER, null, List.of(), actor, KEY);
    }

    public static RequestPostProcessor sign(Realm realm, String entity, List<String> stores, String actor, String key) {
        return request -> {
            byte[] body = request.getContentAsByteArray();
            String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
            var claims = new Claims(realm, entity, stores, actor, System.currentTimeMillis() / 1000 + 60,
                    FoodScopeCredentials.digest(request.getMethod(), path, body == null ? new byte[0] : body));
            request.addHeader(FoodScopeCredentials.HEADER, FoodScopeCredentials.sign(claims, FoodScopeCredentials.key(key)));
            return request;
        };
    }
}

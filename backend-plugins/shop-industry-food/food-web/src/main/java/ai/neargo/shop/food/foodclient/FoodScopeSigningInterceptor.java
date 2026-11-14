package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.scope.FoodScopeBodyScanner;
import ai.neargo.shop.food.scope.FoodScopeCredentials;
import ai.neargo.shop.food.scope.FoodScopeCredentials.Claims;
import ai.neargo.shop.food.scope.FoodScopeCredentials.Rejected;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 宿主发往 food-server 的每个请求在这里签范围凭证（TDD-餐饮系统-内部调用签名凭证）。
 *
 * <p>签之前先拿请求体去对照<b>宿主自己的身份上下文</b>：B 端请求的主体必须是当前商家、门店必须在当前人的授权门店内、
 * 操作人必须是当前登录人；C 端请求里的买家必须是当前登录买家。对不上就不签、请求不发出（403）——
 * 那说明宿主某处业务代码把别人的范围塞进了请求体，food-server 那边也会按同一规则再验一遍。
 *
 * <p>realm 判定：有 {@code BizContext} 商家号 → STAFF；登录人是平台运营 → OPERATOR；有 Web 请求但都不是 → CONSUMER；
 * 没有 Web 请求（后台任务、outbox 消费者）→ SYSTEM。
 */
public final class FoodScopeSigningInterceptor implements ClientHttpRequestInterceptor {
    private final byte[] key;
    private final LongSupplier nowEpochSeconds;

    public FoodScopeSigningInterceptor(byte[] key, LongSupplier nowEpochSeconds) {
        this.key = key;
        this.nowEpochSeconds = nowEpochSeconds;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (key == null) {
            throw new ServiceCallException(CallOutcome.NOT_CONFIGURED, ServiceName.FOOD, 0,
                    "调用 FOOD 的范围签名密钥没配（shop.food.scope-key，至少 " + FoodScopeCredentials.MIN_KEY_BYTES
                            + " 字节）—— 独立部署一律拒绝");
        }
        URI uri = request.getURI();
        String path = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        String digest = FoodScopeCredentials.digest(request.getMethod().name(), path, body);
        Claims claims = claims(FoodScopeBodyScanner.scan(body), digest, nowEpochSeconds.getAsLong()
                + FoodScopeCredentials.TTL_SECONDS);
        request.getHeaders().set(FoodScopeCredentials.HEADER, FoodScopeCredentials.sign(claims, key));
        return execution.execute(request, body);
    }

    static Claims claims(FoodScopeBodyScanner.Scope body, String digest, long exp) {
        Claims claims;
        if (RequestContextHolder.getRequestAttributes() == null) {
            claims = new Claims(FoodScopeCredentials.Realm.SYSTEM, null, List.of(), null, exp, digest);
        } else {
            BizContext context = BizContext.current();
            String user = SecurityUtils.currentUserNoOrNull();
            if (context.merchantNo() != null && !context.merchantNo().isBlank()) {
                // 只申明请求体真用到的门店，且它们都必须在当前人的授权门店内
                for (String store : body.stores())
                    if (!context.storeNos().contains(store)) throw BizException.of(ErrorCode.FORBIDDEN);
                claims = new Claims(FoodScopeCredentials.Realm.STAFF, context.merchantNo(),
                        new ArrayList<>(body.stores()), user, exp, digest);
            } else if (SecurityUtils.currentUser().map(LoginUser::realm).orElse(null) == Realm.OPERATOR) {
                claims = new Claims(FoodScopeCredentials.Realm.OPERATOR, null, List.of(), user, exp, digest);
            } else {
                claims = new Claims(FoodScopeCredentials.Realm.CONSUMER, null, List.of(), user, exp, digest);
            }
        }
        try {
            FoodScopeCredentials.requireBodyMatches(claims, body);
        } catch (Rejected mismatch) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        return claims;
    }
}

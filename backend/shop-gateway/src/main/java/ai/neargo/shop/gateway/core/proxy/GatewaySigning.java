package ai.neargo.shop.gateway.core.proxy;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.gateway.core.end.Access;
import ai.neargo.shop.gateway.core.resource.ApiResource;
import ai.neargo.shop.gateway.core.security.GatewayExchange;
import ai.neargo.shop.svc.InternalServiceInterceptor;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.context.SignedContext;
import ai.neargo.svc.context.SignedContextCodec;
import ai.neargo.svc.context.SignedContextInterceptor;
import ai.neargo.svc.context.SignedContextInterceptor.Caller;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 网关的出站签名（TDD-网关层与统一权限 §2.7；开发计划 S）：把「谁、哪一端、哪家店、判过哪些功能点」签进上下文带给下游。
 *
 * <p>密钥是 {@code shop.gateway.signing.key}，没有默认值；不配就不签，行为与今天相同。
 * 两处用它：转发库里录入的微服务链接（{@link HttpProxyInvoker}），以及发往支付的内部调用。
 */
@Configuration(proxyBeanMethods = false)
public class GatewaySigning {

    private final String key;

    public GatewaySigning(@Value("${shop.gateway.signing.key:}") String key) {
        this.key = key == null ? "" : key.trim();
    }

    public boolean enabled() {
        return !key.isEmpty();
    }

    /** 给一次转发签名，返回要加的请求头；没配密钥返回空。 */
    public Map<String, String> headers(String method, String pathWithQuery, byte[] body) {
        if (!enabled()) {
            return Map.of();
        }
        Caller who = caller();
        String encoded = SignedContextCodec.encode(new SignedContext(who.operator(), who.realm(), who.merchantNo(),
                who.storeNo(), who.points(), UUID.randomUUID().toString(), System.currentTimeMillis(), method,
                pathWithQuery, SignedContextCodec.digest(body)));
        return Map.of(SignedContextCodec.HEADER_CONTEXT, encoded,
                SignedContextCodec.HEADER_SIGNATURE, SignedContextCodec.sign(encoded, key));
    }

    /** 当前请求是替谁发的。没有登录人（定时任务、消息消费）时各项为空。 */
    public Caller caller() {
        LoginUser user = SecurityUtils.currentUser().orElse(null);
        if (user == null) {
            return new Caller(null, null, null, null, List.of(), null);
        }
        String merchantNo = null;
        String storeNo = null;
        if (user.realm() == Realm.MERCHANT) {
            BizContext context = BizContext.current();
            merchantNo = context.merchantNo();
            storeNo = context.currentStoreNo();
        }
        return new Caller(user.userNo(), user.realm().name(), merchantNo, storeNo, judgedPoints(), null);
    }

    /** 网关在这次请求上判过的功能点：命中的资源行上的权限码。没有经过定位（提前判权与转发都没开）时为空。 */
    private static List<String> judgedPoints() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return List.of();
        }
        HttpServletRequest request = attributes.getRequest();
        return GatewayExchange.resource(request)
                .filter(resource -> resource.access() == Access.POINT && resource.rule() != null)
                .map(ApiResource::rule).map(rule -> List.copyOf(rule.codes())).orElse(List.of());
    }

    /** 发往支付的每个内部调用都带上签名上下文；支付那边目前只比对、不拒绝。 */
    @Bean
    InternalServiceInterceptor paySignedContext() {
        return new InternalServiceInterceptor(ServiceName.PAY, new SignedContextInterceptor(() -> key, this::caller));
    }
}

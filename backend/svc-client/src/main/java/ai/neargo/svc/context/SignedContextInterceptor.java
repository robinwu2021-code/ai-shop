package ai.neargo.svc.context;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * 出站签名：给每个内部调用带上签名上下文（TDD-网关层与统一权限 §2.7「出站：一处签名」）。
 *
 * <p>「谁在调」由调用方给（{@link Caller}）；方法、地址、请求体摘要、时间在这里按实际发出的请求填。
 * 没有密钥就什么都不加 —— 不配密钥的部署行为与今天相同。
 */
public class SignedContextInterceptor implements ClientHttpRequestInterceptor {

    /**
     * 这次调用是替谁发的。取不到登录人（定时任务、消息消费）时各项为空，照样签。
     *
     * @param actions 判过的功能点在这个下游服务上允许的动作；没有映射时为空
     */
    public record Caller(String operator, String realm, String merchantNo, String storeNo,
                         List<String> points, String requestId, List<String> actions) {

        public Caller {
            actions = actions == null ? List.of() : List.copyOf(actions);
        }

        /** 不带下游动作。 */
        public Caller(String operator, String realm, String merchantNo, String storeNo,
                      List<String> points, String requestId) {
            this(operator, realm, merchantNo, storeNo, points, requestId, List.of());
        }
    }

    private final Supplier<String> key;
    private final Supplier<Caller> caller;

    public SignedContextInterceptor(Supplier<String> key, Supplier<Caller> caller) {
        this.key = key;
        this.caller = caller;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String secret = key.get();
        if (secret != null && !secret.isBlank()) {
            Caller who = caller.get();
            String path = request.getURI().getRawPath()
                    + (request.getURI().getRawQuery() == null ? "" : "?" + request.getURI().getRawQuery());
            String encoded = SignedContextCodec.encode(new SignedContext(who.operator(), who.realm(),
                    who.merchantNo(), who.storeNo(), who.points(),
                    who.requestId() == null ? UUID.randomUUID().toString() : who.requestId(),
                    System.currentTimeMillis(), request.getMethod().name(), path, SignedContextCodec.digest(body),
                    who.actions()));
            request.getHeaders().set(SignedContextCodec.HEADER_CONTEXT, encoded);
            request.getHeaders().set(SignedContextCodec.HEADER_SIGNATURE, SignedContextCodec.sign(encoded, secret));
        }
        return execution.execute(request, body);
    }
}

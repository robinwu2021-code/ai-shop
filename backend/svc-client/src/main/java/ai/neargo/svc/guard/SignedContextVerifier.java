package ai.neargo.svc.guard;

import ai.neargo.svc.context.SignedContext;
import ai.neargo.svc.context.SignedContextCodec;
import java.time.Duration;
import java.util.Optional;

/**
 * 验签名上下文（TDD-网关层与统一权限 §2.7「入站：微服务侧一个守卫」）。纯函数，不碰 Servlet，方便各服务按自己的方式接。
 *
 * <p>依次验：带没带 → 签名对不对 → 在不在时间窗里 → 方法与地址是不是签的那个 → 请求体是不是签的那个。
 * 任何一步不过都给出具体原因，供日志与「只比对」阶段统计。
 */
public final class SignedContextVerifier {

    /** 验证结论。 */
    public enum Outcome {
        OK, MISSING, MALFORMED, BAD_SIGNATURE, EXPIRED, REQUEST_MISMATCH, BODY_MISMATCH
    }

    /** @param context 只有 {@link Outcome#OK} 时才有值 */
    public record Result(Outcome outcome, Optional<SignedContext> context) {
        public boolean ok() {
            return outcome == Outcome.OK;
        }
    }

    /** 默认时间窗：前后各 5 分钟，容得下机器之间的时钟偏差，又不至于让截获的请求长期可重放。 */
    public static final Duration DEFAULT_WINDOW = Duration.ofMinutes(5);

    private final String key;
    private final Duration window;

    public SignedContextVerifier(String key) {
        this(key, DEFAULT_WINDOW);
    }

    public SignedContextVerifier(String key, Duration window) {
        this.key = key;
        this.window = window;
    }

    /**
     * @param body 请求体；传 null 表示这一处拿不到请求体，不验摘要（方法、地址、签名、时间照验）
     */
    public Result verify(String contextHeader, String signatureHeader, String method, String pathWithQuery,
                         byte[] body, long nowMillis) {
        if (contextHeader == null || contextHeader.isBlank() || signatureHeader == null || signatureHeader.isBlank()) {
            return fail(Outcome.MISSING);
        }
        if (!SignedContextCodec.signatureMatches(contextHeader.trim(), key, signatureHeader)) {
            return fail(Outcome.BAD_SIGNATURE);
        }
        Optional<SignedContext> decoded = SignedContextCodec.decode(contextHeader);
        if (decoded.isEmpty()) {
            return fail(Outcome.MALFORMED);
        }
        SignedContext context = decoded.get();
        if (Math.abs(nowMillis - context.timestamp()) > window.toMillis()) {
            return fail(Outcome.EXPIRED);
        }
        if (context.method() == null || !context.method().equalsIgnoreCase(method)
                || context.path() == null || !context.path().equals(pathWithQuery)) {
            return fail(Outcome.REQUEST_MISMATCH);
        }
        if (body != null && !SignedContextCodec.digest(body).equals(context.bodyDigest())) {
            return fail(Outcome.BODY_MISMATCH);
        }
        return new Result(Outcome.OK, decoded);
    }

    private static Result fail(Outcome outcome) {
        return new Result(outcome, Optional.empty());
    }
}

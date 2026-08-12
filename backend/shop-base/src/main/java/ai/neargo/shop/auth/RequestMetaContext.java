package ai.neargo.shop.auth;

/**
 * 当前请求的 IP / 操作端（ThreadLocal，仿 {@code DataScopeContext}）。
 *
 * <p>审计要记 IP/操作端，但写审计的代码在 platform 域——而领域逻辑不能直接碰
 * {@code HttpServletRequest}（{@code ArchitectureTest.domainsMustNotTouchWebRuntime}
 * 就是防这个：定时任务、事件消费跑在没有 HTTP 请求的线程里，一旦领域代码里混进
 * web 语义，那些场景就调不动它）。所以采用与数据域相同的做法：认证过滤器把值
 * {@link #set} 进来，请求末 {@link #clear}；platform 域只读这个 ThreadLocal，不认 servlet。
 */
public final class RequestMetaContext {

    public record Meta(String ip, String clientType) {
    }

    private static final ThreadLocal<Meta> META = new ThreadLocal<>();

    private RequestMetaContext() {
    }

    public static void set(Meta meta) {
        META.set(meta);
    }

    public static Meta current() {
        return META.get();
    }

    public static void clear() {
        META.remove();
    }
}

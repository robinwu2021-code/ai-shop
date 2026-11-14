package ai.neargo.shop.spi.pay;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 统一封装：调用方一行 {@link #current()} 即得 {@link CallSource}；进程内与 HTTP 两种形态拿到的是同一份（ADR-045 G9）。
 *
 * <p>本模块没有 Servlet，「上游是哪个请求」由各上下文的入口绑定（{@link #bind}）：
 * <ul>
 *   <li>HTTP 请求：web 层的拦截器按可信 IP（{@code ClientMeta}）、端码、操作人、路由模板绑定；</li>
 *   <li>定时任务：任务执行器 {@link #asJob}；</li>
 *   <li>Outbox 消费：分发器 {@link #asOutbox}。</li>
 * </ul>
 * 什么都没绑的线程照样给得出来源（服务、实例、新追踪号），来源端记 {@link #UNKNOWN} —— 留痕不该让调用失败。
 */
public final class CallSources {

    public static final String UNKNOWN = "UNKNOWN";
    public static final String JOB = "JOB";
    public static final String OUTBOX = "OUTBOX";
    public static final String SYSTEM = "SYSTEM";

    /**
     * 上游请求的那一部分来源（每个线程一份）；服务、实例、IP、发起时间在 {@link #current()} 里补。
     *
     * @param uaDigest User-Agent 的 SHA-256 —— 构造时就摘要掉，原文不进线程上下文
     */
    public record Origin(String traceId, String api, String client, String ip, String uaDigest, String operatorNo) {

        /** HTTP 请求：{@code userAgent} 传原文，这里摘要 */
        public static Origin http(String traceId, String api, String client, String ip, String userAgent,
                                  String operatorNo) {
            return new Origin(traceId == null || traceId.isBlank() ? newTraceId() : traceId, api, client, ip,
                    digest(userAgent), operatorNo);
        }
    }

    /** 绑定的作用域：关掉即恢复绑定前的来源（可嵌套） */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private static final ThreadLocal<Origin> ORIGIN = new ThreadLocal<>();
    private static volatile String service;
    private static final String INSTANCE;
    private static final String SERVER_IP;

    static {
        String host = UNKNOWN;
        String ip = UNKNOWN;
        try {
            InetAddress local = InetAddress.getLocalHost();
            host = local.getHostName();
            ip = local.getHostAddress();
        } catch (Exception unresolvable) {
            // 容器里主机名解析不出很常见：照样留痕，记 UNKNOWN
        }
        INSTANCE = host;
        SERVER_IP = ip;
    }

    private CallSources() {
    }

    /** 启动时由宿主设一次服务名（{@code spring.application.name}）；{@code null} = 未配置 */
    public static void configure(String serviceName) {
        service = serviceName == null || serviceName.isBlank() ? null : serviceName;
    }

    public static Scope bind(Origin origin) {
        Origin previous = ORIGIN.get();
        ORIGIN.set(origin);
        return () -> {
            if (previous == null) {
                ORIGIN.remove();
            } else {
                ORIGIN.set(previous);
            }
        };
    }

    /** 在定时任务的来源下跑一段：来源端 JOB、上游接口 = 任务名、操作人 SYSTEM */
    public static <T> T asJob(String jobName, Supplier<T> body) {
        try (Scope ignored = bind(new Origin(newTraceId(), jobName, JOB, null, null, SYSTEM))) {
            return body.get();
        }
    }

    /** 在 Outbox 消费的来源下跑一段：来源端 OUTBOX、上游接口 = 事件类型、操作人 SYSTEM */
    public static <T> T asOutbox(String eventType, Supplier<T> body) {
        try (Scope ignored = bind(new Origin(newTraceId(), eventType, OUTBOX, null, null, SYSTEM))) {
            return body.get();
        }
    }

    public static CallSource current() {
        Origin o = ORIGIN.get();
        String svc = service == null ? UNKNOWN : service;
        String now = Instant.now().toString();
        if (o == null) {
            return new CallSource(svc, null, INSTANCE, SERVER_IP, newTraceId(), null, UNKNOWN, null, null, null, now);
        }
        return new CallSource(svc, null, INSTANCE, SERVER_IP, o.traceId(), o.api(), o.client(), o.ip(), o.uaDigest(),
                o.operatorNo(), now);
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String digest(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

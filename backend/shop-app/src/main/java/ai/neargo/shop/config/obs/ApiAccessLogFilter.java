package ai.neargo.shop.config.obs;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * 接口耗时与慢日志（TDD-接口耗时与慢日志）。
 *
 * <p><b>为什么要有它</b>：2026-10-08 实测，全链路一个耗时数字都没有 ——
 * 应用日志当天到 08:10 只有 29 KB、nginx 的 log_format 是默认 combined（没有
 * {@code $request_time}）、actuator 只暴露了 health/info。出了「某个接口变慢」
 * 这类问题，今天没有任何地方查得到。
 *
 * <p><b>量级</b>：昨天打到后端 3352 个请求，每条约 200 字节 ≈ 670 KB/天，
 * 而轮转是 20MB × 14 天 —— 装得下。这个数要写出来，因为上一次生产事故
 * 正是「日志无轮转 + outbox 无上限」把盘写满的。
 */
public class ApiAccessLogFilter extends OncePerRequestFilter {

    /** 全量访问行。一行一个请求，`key=value` 固定顺序 —— 报告脚本按它解析 */
    private static final Logger ACCESS = LoggerFactory.getLogger("api.access");
    /**
     * 慢请求。**故意用另一个 logger 名而不是另一个文件**：
     * 上一次生产盘满的根因就是「日志无轮转」，再开一个文件就要再配一套轮转，
     * 多一个会忘的地方。靠 logger 名就能捞（`grep ' api.slow '`），报告脚本同理。
     */
    private static final Logger SLOW = LoggerFactory.getLogger("api.slow");

    /**
     * 号段脱敏。**只在拿不到路径模板时用**（认证失败的请求到不了 dispatcher，
     * `BEST_MATCHING_PATTERN` 为空）。不脱敏的话 `/mp/goods/G2026…` 会把同一个接口
     * 散成几千行，聚合出来每行 count=1 —— 等于没聚合。
     *
     * <p>覆盖本仓库的号段前缀（G/M/ST/SK/U/O/…）＋ 纯数字段。
     */
    private static final Pattern ID_SEG = Pattern.compile(
            "/(?:[A-Z]{1,3}2\\d{12,}[A-Za-z0-9]*|\\d{4,})(?=/|$)");

    private final AntPathMatcher matcher = new AntPathMatcher();
    private final boolean accessLog;
    private final long slowMs;
    private final List<String> exclude;

    public ApiAccessLogFilter(boolean accessLog, long slowMs, List<String> exclude) {
        this.accessLog = accessLog;
        this.slowMs = slowMs;
        this.exclude = exclude == null ? List.of() : exclude;
    }

    /**
     * 排除两类，理由各不相同：
     * <ul>
     *   <li>{@code /actuator/**}：部署脚本的 health 探针一直在打，记它只是噪声；
     *   <li>{@code /ops/stream}：SSE 长连接，一挂几分钟。过滤器量出来就是「超慢」，
     *       **会把慢日志整个淹掉** —— 而它慢是设计如此，不是故障。
     * </ul>
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String uri = req.getRequestURI();
        for (String p : exclude) {
            if (matcher.match(p, uri)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        long t0 = System.nanoTime();
        String rid = Long.toHexString(ThreadLocalRandom.current().nextLong() & 0xFFFF_FFFFL);
        try {
            chain.doFilter(req, resp);
        } finally {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            String line = line(req, resp, ms, rid);
            if (accessLog) {
                ACCESS.info("{}", line);
            }
            if (ms >= slowMs) {
                SLOW.warn("{}", line);
            }
        }
    }

    private String line(HttpServletRequest req, HttpServletResponse resp, long ms, String rid) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("api m=").append(req.getMethod())
                .append(" p=").append(path(req))
                .append(" s=").append(resp.getStatus())
                .append(" ms=").append(ms)
                .append(" rid=").append(rid);
        /*
         * **门店号取自请求头，不取 BizContext。**
         *
         * 一开始写的是 `BizContext.current()`，错的：`BizContextFilter` 在链的**更里层**，
         * 而它在自己的 `finally` 里 `clear()` —— 内层的 finally 比外层先跑，
         * 所以等本过滤器的 finally 执行时 ThreadLocal 已经空了。
         * 那样写不会报错，只会让 `store=` **永远不出现** —— 而「字段恒缺失」
         * 和「这些请求本来就没带门店」在日志里长得一模一样，没人会发现。
         *
         * 请求头在任何时候都读得到，也不让可观测层耦合认证层。代价是拿不到 merchantNo
         * （它只在 BizContext 里），本版就不记 —— 真要的话得让 BizContextFilter
         * 往 request attribute 里存一份，那是另一件事。
         *
         * **只带号，不带任何人的信息**：查询串、请求体、手机号、地址一律不记 ——
         * 排查价值远低于泄露风险，而慢日志是会被 grep、会被贴进聊天框的东西。
         */
        appendIfPresent(sb, " store=", req.getHeader("X-Store-No"));
        return sb.toString();
    }

    private static void appendIfPresent(StringBuilder sb, String key, String v) {
        if (v != null && !v.isBlank()) {
            sb.append(key).append(v);
        }
    }

    /**
     * 路径：**优先用模板**（`/mp/goods/{goodsNo}`），聚合才有意义。
     * 拿不到就脱敏原始路径 —— 见 {@link #ID_SEG}。
     */
    private String path(HttpServletRequest req) {
        Object tpl = req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (tpl instanceof String s && !s.isBlank()) {
            return s;
        }
        return ID_SEG.matcher(req.getRequestURI()).replaceAll("/*");
    }
}

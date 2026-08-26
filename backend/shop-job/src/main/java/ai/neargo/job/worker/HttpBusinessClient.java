package ai.neargo.job.worker;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 与业务系统之间的全部通信。**JDK 自带的 HttpClient，不引任何 HTTP 库。**
 *
 * <p>两条不显然的实现约定：
 * <ol>
 *   <li><b>锁 HTTP/1.1。</b>本仓库踩过：某些服务端对 HTTP/2 的大 body 直接回 400，
 *       而错误信息完全不指向协议版本。这里的 body 很小，但锁住是免费的，
 *       而不锁的话那个坑会在某天换了反向代理时突然出现。</li>
 *   <li><b>日志只记状态码与异常类名，不记 body。</b>请求体里有 {@code params}，
 *       将来可能带业务标识；响应体里有 {@code detail}，同理。
 *       调度器的日志不该成为一个额外的数据出口。</li>
 * </ol>
 */
class HttpBusinessClient implements JobInvoker, JobDeclarationSource {

    private static final Logger log = LoggerFactory.getLogger(HttpBusinessClient.class);

    /** 业务侧内部端点的前缀。**不经 nginx**，走 127.0.0.1。 */
    static final String RUN_PATH = "/internal/job/%s/run";
    static final String DECLARATIONS_PATH = "/internal/job/declarations";
    static final String TOKEN_HEADER = "X-Job-Token";

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ObjectMapper json = new ObjectMapper();
    private final JobWorkerProperties props;

    HttpBusinessClient(JobWorkerProperties props) {
        this.props = props;
    }

    @Override
    public InvokeOutcome invoke(String target, String handlerName, JobInvocation in, int timeoutSec) {
        String base = props.getTargets().get(target);
        if (base == null) {
            // 配置缺失与网络故障要分开：前者改配置，后者等业务起来。混成一种，运维会等一个永远不来的恢复。
            return InvokeOutcome.of(JobStatus.UNREACHABLE, "没有配置 target=" + target + " 的地址",
                    "MissingTargetConfig", null);
        }
        String body = json.writeValueAsString(new LinkedHashMap<>(Map.of(
                "runId", in.runId(),
                "triggerType", in.type().name(),
                "bizDate", in.bizDate() == null ? "" : in.bizDate().toString(),
                "params", in.params())));

        HttpRequest req = HttpRequest.newBuilder(URI.create(base + RUN_PATH.formatted(handlerName)))
                .header("Content-Type", "application/json")
                .header(TOKEN_HEADER, props.getToken())
                .timeout(Duration.ofSeconds(timeoutSec))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            return readOutcome(res);
        } catch (java.net.http.HttpTimeoutException e) {
            return InvokeOutcome.timeout(timeoutSec);
        } catch (Exception e) {
            log.warn("调用业务系统失败 handler={} target={} 异常={}",
                    handlerName, target, e.getClass().getSimpleName());
            return InvokeOutcome.unreachable(e.getClass().getSimpleName());
        }
    }

    private InvokeOutcome readOutcome(HttpResponse<String> res) {
        int code = res.statusCode();
        if (code == 409) {
            // 锁没抢到。**正常的并发保护，不是故障** —— 不能计入连续失败
            return InvokeOutcome.of(JobStatus.SKIPPED, "上一轮仍在执行，本轮跳过", null, code);
        }
        if (code == 404) {
            return InvokeOutcome.of(JobStatus.FAILED, "业务系统里没有这个 handler",
                    "HandlerNotFound", code);
        }
        if (code / 100 != 2) {
            return InvokeOutcome.of(JobStatus.FAILED, "业务系统返回 " + code, "Http" + code, code);
        }
        try {
            JsonNode n = json.readTree(res.body());
            String status = text(n, "status");
            JobStatus parsed = status == null ? JobStatus.SUCCESS : JobStatus.valueOf(status);
            return InvokeOutcome.of(parsed, text(n, "detail"), text(n, "error"), code);
        } catch (Exception e) {
            // 200 但回了看不懂的东西。**算失败而不是成功** —— 说不清跑没跑，就不能当跑成了
            return InvokeOutcome.of(JobStatus.FAILED, "业务系统返回的内容解析不了",
                    e.getClass().getSimpleName(), code);
        }
    }

    @Override
    public List<JobDeclaration> fetch(String target) {
        String base = props.getTargets().get(target);
        if (base == null) {
            throw new IllegalStateException("没有配置 target=" + target + " 的地址");
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + DECLARATIONS_PATH))
                .header(TOKEN_HEADER, props.getToken())
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw new IllegalStateException("取任务声明失败，HTTP " + res.statusCode());
            }
            List<JobDeclaration> out = new ArrayList<>();
            for (JsonNode n : json.readTree(res.body())) {
                out.add(new JobDeclaration(
                        text(n, "handlerName"), text(n, "displayName"), text(n, "description"),
                        text(n, "ownerModule"), text(n, "defaultCron"),
                        n.path("enabled").asBoolean(true),
                        n.path("timeoutSec").asInt(60),
                        n.path("lockAtMostSec").asInt(1800),
                        n.path("manualTrigger").asBoolean(true),
                        n.path("logEveryRun").asBoolean(true)));
            }
            return out;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("取任务声明失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() || v.asString().isEmpty() ? null : v.asString();
    }
}

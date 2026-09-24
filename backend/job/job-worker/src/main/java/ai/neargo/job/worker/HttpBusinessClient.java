package ai.neargo.job.worker;

import ai.neargo.job.engine.InvokeOutcome;
import ai.neargo.job.engine.JobDeclarationSource;
import ai.neargo.job.engine.JobInvoker;
import ai.neargo.job.engine.JobWorkerProperties;
import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHttpPaths;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobStatus;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import ai.neargo.svc.client.ServiceClientSpec;
import ai.neargo.svc.client.ServiceClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 与业务系统之间的全部通信，经 {@link JobBusinessApi}（{@code @HttpExchange}）发出。
 *
 * <p>传输规矩 —— 锁 HTTP/1.1、日志不记 body —— 现在由 svc-client 统一执行，
 * 这里不再各写一遍（见 {@code ServiceClients} 的类注释，那两条的来历也记在那里）。
 *
 * <p>这里只管<b>把结局翻译成任务状态</b>，这套映射与迁移前逐条一致：
 * <ul>
 *   <li>没配地址 → {@code UNREACHABLE}（错误名 {@code MissingTargetConfig}）；</li>
 *   <li>409 → {@code SKIPPED}：锁没抢到是正常的并发保护，不计入连续失败；</li>
 *   <li>404 → {@code FAILED}（{@code HandlerNotFound}）；其它非 2xx → {@code FAILED}（{@code Http<码>}）；</li>
 *   <li>2xx 但内容解析不了 → {@code FAILED}：说不清跑没跑，就不能当跑成了；</li>
 *   <li>超时 → {@code TIMEOUT}；连不上 → {@code UNREACHABLE}（错误名是传输层异常的类名）。</li>
 * </ul>
 *
 * <p><b>读超时按任务各自的 {@code timeoutSec}</b>：代理实例按 (target, 超时) 缓存，
 * 任务种类有限、超时取值更少，缓存不会长大。
 */
class HttpBusinessClient implements JobInvoker, JobDeclarationSource {

    private static final Logger log = LoggerFactory.getLogger(HttpBusinessClient.class);

    /** 取任务声明的读超时：它不跑任务，只列清单 */
    private static final Duration DECLARATIONS_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final ObjectMapper json = new ObjectMapper();
    private final JobWorkerProperties props;
    private final Map<String, JobBusinessApi> apis = new ConcurrentHashMap<>();

    HttpBusinessClient(JobWorkerProperties props) {
        this.props = props;
    }

    /** 同一个 target、同一个读超时共用一个代理 */
    private JobBusinessApi api(String target, Duration readTimeout) {
        return apis.computeIfAbsent(target + "|" + readTimeout.toMillis(), k -> ServiceClients.create(
                JobBusinessApi.class,
                new ServiceClientSpec(target,
                        name -> Optional.ofNullable(props.getTargets().get(name)),
                        JobHttpPaths.TOKEN_HEADER,
                        props::getToken,
                        // 令牌为空时照样发 —— 与迁移前一致；是否拒绝由业务侧判（它会回 401）
                        false,
                        CONNECT_TIMEOUT,
                        readTimeout)));
    }

    @Override
    public InvokeOutcome invoke(String target, String handlerName, JobInvocation in, int timeoutSec) {
        if (props.getTargets().get(target) == null) {
            // 配置缺失与网络故障要分开：前者改配置，后者等业务起来。混成一种，运维会等一个永远不来的恢复。
            return InvokeOutcome.of(JobStatus.UNREACHABLE, "没有配置 target=" + target + " 的地址",
                    "MissingTargetConfig", null);
        }
        Map<String, Object> body = new LinkedHashMap<>(Map.of(
                "runId", in.runId(),
                "triggerType", in.type().name(),
                "bizDate", in.bizDate() == null ? "" : in.bizDate().toString(),
                "params", in.params()));
        try {
            ResponseEntity<String> res = ServiceCalls.call(target,
                    () -> api(target, Duration.ofSeconds(timeoutSec)).run(handlerName, body));
            return readOutcome(res.getStatusCode().value(), res.getBody());
        } catch (ServiceCallException e) {
            return switch (e.outcome()) {
                case TIMEOUT -> InvokeOutcome.timeout(timeoutSec);
                case REMOTE_ERROR -> readError(e.statusCode(), e);
                case NOT_CONFIGURED -> InvokeOutcome.of(JobStatus.UNREACHABLE,
                        "没有配置 target=" + target + " 的地址", "MissingTargetConfig", null);
                case UNREACHABLE, OK -> {
                    log.warn("调用业务系统失败 handler={} target={} 异常={}", handlerName, target, e.causeKind());
                    yield InvokeOutcome.unreachable(e.causeKind());
                }
            };
        }
    }

    /** 对方应答了一个非 2xx（或读不了的应答，此时没有状态码） */
    private InvokeOutcome readError(int code, ServiceCallException e) {
        if (code == 409) {
            // 锁没抢到。**正常的并发保护，不是故障** —— 不能计入连续失败
            return InvokeOutcome.of(JobStatus.SKIPPED, "上一轮仍在执行，本轮跳过", null, code);
        }
        if (code == 404) {
            return InvokeOutcome.of(JobStatus.FAILED, "业务系统里没有这个 handler",
                    "HandlerNotFound", code);
        }
        if (code == 0) {
            return InvokeOutcome.of(JobStatus.FAILED, "业务系统返回的内容解析不了", e.causeKind(), null);
        }
        return InvokeOutcome.of(JobStatus.FAILED, "业务系统返回 " + code, "Http" + code, code);
    }

    /** 2xx：按应答体里的 status 判；没写 status 算成功，解析不了算失败 */
    private InvokeOutcome readOutcome(int code, String responseBody) {
        try {
            JsonNode n = json.readTree(responseBody == null ? "" : responseBody);
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
        if (props.getTargets().get(target) == null) {
            throw new IllegalStateException("没有配置 target=" + target + " 的地址");
        }
        String body;
        try {
            body = ServiceCalls.call(target, () -> api(target, DECLARATIONS_TIMEOUT).declarations());
        } catch (ServiceCallException e) {
            throw new IllegalStateException(e.statusCode() > 0
                    ? "取任务声明失败，HTTP " + e.statusCode()
                    : "取任务声明失败：" + e.causeKind(), e);
        }
        try {
            List<JobDeclaration> out = new ArrayList<>();
            for (JsonNode n : json.readTree(body == null ? "" : body)) {
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
            // 与迁移前一致：解析失败原样抛出，由同步服务按「这一轮没取到」处理
            throw e;
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() || v.asString().isEmpty() ? null : v.asString();
    }
}

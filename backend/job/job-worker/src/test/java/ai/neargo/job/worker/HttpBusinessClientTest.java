package ai.neargo.job.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobStatus;
import ai.neargo.job.api.TriggerType;
import ai.neargo.job.engine.InvokeOutcome;
import ai.neargo.job.engine.JobWorkerProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 调度器 → 业务侧：迁到 {@code @HttpExchange} 之后，**每一条结局映射与迁移前逐条一致**。
 * 真实 socket（JDK {@link HttpServer}），不用替身 —— 超时与连接拒绝只有真 socket 才测得到。
 */
@DisplayName("调度器调业务侧")
class HttpBusinessClientTest {

    private final List<String> seen = new CopyOnWriteArrayList<>();
    private final List<HttpServer> servers = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        servers.forEach(s -> s.stop(0));
    }

    /** @param sleepMs 应答前睡多久（测读超时用） */
    private String server(int status, String body, long sleepMs) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            String req = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath()
                    + " token=" + ex.getRequestHeaders().getFirst("X-Job-Token") + " body=" + req);
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                ex.getResponseBody().write(bytes);
            }
            ex.close();
        });
        s.start();
        servers.add(s);
        return "http://127.0.0.1:" + s.getAddress().getPort();
    }

    private static HttpBusinessClient client(String base) {
        JobWorkerProperties p = new JobWorkerProperties();
        if (base != null) {
            p.setTargets(new java.util.LinkedHashMap<>(Map.of("shop", base)));
        }
        p.setToken("tk");
        return new HttpBusinessClient(p);
    }

    private static JobInvocation run() {
        return new JobInvocation("run-1", TriggerType.MANUAL, LocalDate.of(2026, 9, 24), Map.of("k", "v"));
    }

    @Test
    @DisplayName("★★★ 2xx：按应答体的 status 判；路径、令牌头、请求体都到了对方")
    void success() throws IOException {
        HttpBusinessClient c = client(server(200, "{\"status\":\"SUCCESS\",\"detail\":\"跑完了\"}", 0));

        InvokeOutcome o = c.invoke("shop", "demo", run(), 5);

        assertThat(o.status()).isEqualTo(JobStatus.SUCCESS);
        assertThat(o.detail()).isEqualTo("跑完了");
        assertThat(o.httpStatus()).isEqualTo(200);
        assertThat(seen).singleElement().satisfies(line -> assertThat(line)
                .startsWith("POST /internal/job/demo/run token=tk body=")
                .contains("\"runId\":\"run-1\"", "\"triggerType\":\"MANUAL\"", "\"bizDate\":\"2026-09-24\"",
                        "\"params\":{\"k\":\"v\"}"));
    }

    @Test
    @DisplayName("★★ 2xx 没写 status：算成功（与迁移前一致）")
    void successWithoutStatus() throws IOException {
        InvokeOutcome o = client(server(200, "{}", 0)).invoke("shop", "demo", run(), 5);
        assertThat(o.status()).isEqualTo(JobStatus.SUCCESS);
    }

    @Test
    @DisplayName("★★★ 409：跳过，不是失败 —— 锁没抢到是正常的并发保护，不能计入连续失败")
    void conflictIsSkipped() throws IOException {
        InvokeOutcome o = client(server(409, "{}", 0)).invoke("shop", "demo", run(), 5);
        assertThat(o.status()).isEqualTo(JobStatus.SKIPPED);
        assertThat(o.httpStatus()).isEqualTo(409);
    }

    @Test
    @DisplayName("★★ 404：失败，错误名 HandlerNotFound")
    void notFound() throws IOException {
        InvokeOutcome o = client(server(404, "", 0)).invoke("shop", "demo", run(), 5);
        assertThat(o.status()).isEqualTo(JobStatus.FAILED);
        assertThat(o.error()).isEqualTo("HandlerNotFound");
    }

    @Test
    @DisplayName("★★ 其它非 2xx：失败，错误名 Http<码>")
    void serverError() throws IOException {
        InvokeOutcome o = client(server(500, "{\"oops\":1}", 0)).invoke("shop", "demo", run(), 5);
        assertThat(o.status()).isEqualTo(JobStatus.FAILED);
        assertThat(o.error()).isEqualTo("Http500");
        assertThat(o.detail()).as("不把应答体带进运行记录").doesNotContain("oops");
    }

    @Test
    @DisplayName("★★★ 2xx 但内容解析不了：失败 —— 说不清跑没跑，就不能当跑成了")
    void unparseableIsFailed() throws IOException {
        InvokeOutcome o = client(server(200, "<html>", 0)).invoke("shop", "demo", run(), 5);
        assertThat(o.status()).isEqualTo(JobStatus.FAILED);
        assertThat(o.httpStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("★★★ 没配地址：不可达，错误名 MissingTargetConfig，请求没发出去")
    void missingTarget() {
        InvokeOutcome o = client(null).invoke("shop", "demo", run(), 5);
        assertThat(o.status()).isEqualTo(JobStatus.UNREACHABLE);
        assertThat(o.error()).isEqualTo("MissingTargetConfig");
        assertThat(seen).isEmpty();
    }

    @Test
    @DisplayName("★★★ 对方没起：不可达，错误名是传输层异常的类名")
    void refused() throws IOException {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        InvokeOutcome o = client("http://127.0.0.1:" + port).invoke("shop", "demo", run(), 5);
        assertThat(o.status()).isEqualTo(JobStatus.UNREACHABLE);
        assertThat(o.error()).endsWith("Exception");
    }

    @Test
    @DisplayName("★★★ 超时按任务各自的 timeoutSec：同一个慢服务，1 秒的任务超时、3 秒的任务跑完")
    void timeoutIsPerJob() throws IOException {
        HttpBusinessClient c = client(server(200, "{\"status\":\"SUCCESS\"}", 1500));

        assertThat(c.invoke("shop", "fast", run(), 1).status()).isEqualTo(JobStatus.TIMEOUT);
        assertThat(c.invoke("shop", "slow", run(), 3).status()).isEqualTo(JobStatus.SUCCESS);
    }

    @Test
    @DisplayName("★★ 取任务声明：缺字段取默认值（与迁移前一致，不交给反序列化去填 null / 0）")
    // 不能两个超时字段都缺：60 与 1800 这对默认值会被 JobDeclaration 自己的校验拒掉（差 30 倍）
    void declarationsDefaults() throws IOException {
        List<JobDeclaration> ds = client(server(200, "[{\"handlerName\":\"a\",\"displayName\":\"甲\",\"defaultCron\":\"0 0 * * * *\","
                + "\"timeoutSec\":1500}]", 0))
                .fetch("shop");

        assertThat(ds).singleElement().satisfies(d -> {
            assertThat(d.handlerName()).isEqualTo("a");
            assertThat(d.enabled()).isTrue();
            assertThat(d.timeoutSec()).as("给了就用给的").isEqualTo(1500);
            assertThat(d.lockAtMostSec()).as("没给取默认 1800").isEqualTo(1800);
            assertThat(d.manualTrigger()).isTrue();
        });
        assertThat(seen).singleElement().satisfies(line ->
                assertThat(line).startsWith("GET /internal/job/declarations token=tk"));
    }

    @Test
    @DisplayName("★★ 取任务声明遇到非 2xx：抛出，消息里带状态码")
    void declarationsHttpError() throws IOException {
        HttpBusinessClient c = client(server(503, "", 0));
        assertThatThrownBy(() -> c.fetch("shop"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 503");
    }
}

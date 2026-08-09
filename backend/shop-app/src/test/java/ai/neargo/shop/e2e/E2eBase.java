package ai.neargo.shop.e2e;

import ai.neargo.shop.common.OtpStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E 基类：**真 Web 容器 + 真 MariaDB + 真 HTTP**。
 *
 * <h3>它与 240 条集成测试的分工</h3>
 * 那些用 MockMvc + H2，跑得快、覆盖广，进默认构建；
 * 这里少而关键，验的是<b>「声明对、跑起来也对」</b> —— 本轮真库暴露、
 * 而 H2 全绿的缺陷有三个（VO 字段名、待审队列没过滤、数据域把商家订单过滤成空），
 * 它们的共同点是：**跨了真 HTTP 栈或真数据库才看得见**。
 *
 * <h3>为什么不进 `mvn test`</h3>
 * 它依赖一个跑着的 MariaDB。在没有数据库的机器上会红 ——
 * 而「构建在别人机器上红」会让人开始忽略红灯。用 {@code @Tag("e2e")} 隔开，
 * 只有 {@code mvn verify -Pe2e} 才跑。
 *
 * <h3>库的重置策略</h3>
 * <b>每个测试类跑前 clean + migrate 一次</b>。
 * 每个方法都重置太慢（迁移要整跑一遍）；完全不重置则用例互相污染 ——
 * 本轮已经踩过一次：新增商家把另一条用例的断言目标挤出了分页第一页，
 * 而那时报的是「商家不可见」，与真正的可见性缺陷长得一模一样。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("e2e")
@Tag("e2e")
public abstract class E2eBase {

    /** 手机号计数器：并行/连续跑时不撞号 —— 撞号会让「一人一份进行中申请」误判 */
    private static final AtomicInteger PHONE_SEQ = new AtomicInteger(0);

    @LocalServerPort
    protected int port;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected OtpStore otpStore;

    @Autowired
    private Flyway flyway;

    /**
     * 演示数据播种器。
     *
     * <p>清库发生在 Spring 启动**之后**，会把启动时播的种子一起清掉 ——
     * 于是运营账号、社区（CM001）、自提点（PP0001）全没了，
     * 而旅程里第一步就要用运营账号。所以清完要重放一次。
     */
    @Autowired
    private org.springframework.boot.ApplicationRunner seedRunner;

    /** 最后一次响应体全文。失败时打出来 —— 字段错配这类问题看一眼响应就够了 */
    protected String lastBody;

    private static boolean resetDone;

    @BeforeAll
    static void resetOnce() {
        // 实例级重置在 setUpDatabase 里做；这里只是把标记复位，让每个测试类各重置一次
        resetDone = false;
    }

    /**
     * 每个测试类重置一次库。
     *
     * <p>用 Flyway `clean + migrate` 而不是 `TRUNCATE`：后者要维护一张表清单，
     * 而清单每次加表都会漏 —— 漏掉的表里留着上一轮的数据，
     * 表现是「某条用例单跑绿、整批跑红」，最难查的一类不稳定。
     */
    protected void resetDatabaseOnce() {
        if (resetDone) {
            return;
        }
        flyway.clean();
        flyway.migrate();
        try {
            // 重放种子：运营账号与社区/自提点是旅程的前置，清库把它们一起清了
            seedRunner.run(new org.springframework.boot.DefaultApplicationArguments());
        } catch (Exception e) {
            throw new IllegalStateException("E2E 种子重放失败 —— 没有运营账号旅程走不下去", e);
        }
        resetDone = true;
    }

    // ------------------------------------------------------------------ HTTP

    protected RestClient http() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** GET，返回 `data` 节点；非 0 业务码直接失败并打出全文 */
    protected JsonNode get(String path, String token, String storeNo) {
        String body = http().get().uri(path)
                .headers(h -> auth(h, token, storeNo))
                .retrieve().body(String.class);
        return unwrap(body, "GET " + path);
    }

    protected JsonNode get(String path, String token) {
        return get(path, token, null);
    }

    protected JsonNode post(String path, String token, Object payload) {
        return post(path, token, null, payload);
    }

    protected JsonNode post(String path, String token, String storeNo, Object payload) {
        var spec = http().post().uri(path).headers(h -> auth(h, token, storeNo));
        if (payload != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(payload);
        }
        return unwrap(spec.retrieve().body(String.class), "POST " + path);
    }

    /**
     * 期待业务失败：返回错误码。**成功时反而要报错** —— 该拦没拦是最坏的结果。
     *
     * <p>不让客户端在 4xx/5xx 上抛异常：业务错误走的是「HTTP 200 + code≠0」，
     * 但鉴权失败是真的 401 —— 两种都要能读到响应体，否则失败信息只剩一个状态码。
     */
    protected int expectFail(String method, String path, String token, Object payload) {
        var spec = "POST".equals(method)
                ? http().post().uri(path).headers(h -> auth(h, token, null))
                        .contentType(MediaType.APPLICATION_JSON).body(payload == null ? Map.of() : payload)
                        .retrieve()
                : http().get().uri(path).headers(h -> auth(h, token, null)).retrieve();
        String body = spec.onStatus(status -> true, (req, res) -> { }).body(String.class);
        lastBody = body;
        JsonNode root = json.readTree(body);
        JsonNode code = root.get("code");
        assertThat(code)
                .as("%s %s 的失败响应不符合统一信封：%s", method, path, body)
                .isNotNull();
        assertThat(code.asInt())
                .as("%s %s 本该被拒，却成功了。响应：%s", method, path, body)
                .isNotZero();
        return code.asInt();
    }

    private void auth(HttpHeaders h, String token, String storeNo) {
        if (token != null && !token.isBlank()) {
            h.setBearerAuth(token);
        }
        if (storeNo != null && !storeNo.isBlank()) {
            // 与端上同一个头：当前门店是会话上下文，不是查询条件
            h.set("X-Store-No", storeNo);
        }
    }

    private JsonNode unwrap(String body, String what) {
        lastBody = body;
        JsonNode root = json.readTree(body);
        JsonNode code = root.get("code");
        assertThat(code).as("%s 的响应不符合统一信封：%s", what, body).isNotNull();
        assertThat(code.asInt()).as("%s 失败：%s", what, body).isZero();
        return root.get("data");
    }

    // ------------------------------------------------------------------ 登录

    /** 生成一个本轮不会重复的手机号 */
    protected String nextPhone() {
        return "1330000%04d".formatted(PHONE_SEQ.incrementAndGet());
    }

    /** 消费者登录（登录即注册） */
    protected String loginConsumer(String phone) {
        post("/mp/user/otp/send", null, Map.of("phone", phone));
        String code = otpStore.peek(phone).orElseThrow(
                () -> new IllegalStateException("没拿到验证码，OtpStore 是不是换实现了？phone=" + phone));
        var data = post("/mp/user/login", null, new LinkedHashMap<>(Map.of(
                "grantType", "PHONE_OTP", "principal", phone, "credential", code, "agreed", true)));
        return data.get("token").asString();
    }

    /** 员工登录（商家账号那条路，不建 C 端账号） */
    protected String loginStaff(String phone) {
        post("/mp/user/otp/send", null, Map.of("phone", phone));
        String code = otpStore.peek(phone).orElseThrow();
        return post("/biz/auth/staff-login", null, Map.of("phone", phone, "code", code))
                .get("token").asString();
    }

    protected String loginOps(String username, String password) {
        return post("/ops/auth/login", null, Map.of("username", username, "password", password))
                .get("token").asString();
    }

    // ------------------------------------------------------------------ 步骤日志

    /**
     * 打印步骤。**E2E 失败时离现场最远** ——
     * 没有步骤日志就只能重跑一遍看它断在哪。
     */
    protected void step(int no, String what, Object detail) {
        System.out.printf("  [%02d] %-36s %s%n", no, what, detail == null ? "" : detail);
    }
}

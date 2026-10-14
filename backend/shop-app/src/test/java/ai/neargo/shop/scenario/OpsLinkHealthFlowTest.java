package ai.neargo.shop.scenario;

import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.event.SysOutboxMapper;
import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 投影链路健康度（M3）。
 *
 * <p><b>验收判据就是 2026-09-02 那次事故</b>：投递任务停着，一条 {@code SKU_UPSERTED}
 * 在队列里躺了六个小时，那件货在库存里不存在，而任何地方都不会报错 ——
 * 它当时在运营端的唯一痕迹是「库存对差」里的「待搬 1 个」。
 * <b>这一页必须让那次一眼看得出。</b>所以测试直接把那个场景种出来。
 *
 * <p><b>断言全部写成「种之前 → 种之后」的差值与转移</b>，不是绝对值。
 * 第一版写的是绝对值（「种之前必须是 OK」），单独跑绿、全量跑红 ——
 * 别的场景用例会在 {@code sys_outbox} 里留下 PENDING 事件，
 * 而那些事件与本用例毫不相干。差值对环境噪声免疫，且它证明的东西一点没少：
 * <b>是我种的这一条让结论变了</b>。
 *
 * <p>第二条守的是这一页真正的信息量：<b>积压里混着两种东西</b>。
 * 两个投递任务失败时都把事件留在 {@code PENDING}、只加 {@code retry_count}，
 * 所以「没人碰过」（投递停了）与「一直在失败」（消费者坏了）在 status 上长得一模一样，
 * 而这两件事该找的人完全不同。
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsLinkHealthFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private SysOutboxMapper outbox;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    private JsonNode platformChannel() throws Exception {
        String body = mvc().perform(get("/ops/inventory/link-health")
                        .header("Authorization", "Bearer " + TestLogin.admin(mvc(), json)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode rows = json.readTree(body).get("data");
        assertThat(rows.size())
                .as("恒该两行（两条方向各一行）—— 少一行是查不到，不是那条链路没事")
                .isEqualTo(2);
        for (JsonNode r : rows) {
            if ("PLATFORM_TO_INVENTORY".equals(r.get("channel").asString())) {
                return r;
            }
        }
        throw new AssertionError("没有 PLATFORM_TO_INVENTORY 这一行");
    }

    @Test
    @DisplayName("★★★ 09-02 复现：投递停着、一条躺了六小时 —— 这一页必须说「投递任务没在跑」")
    void theSixHourStallIsVisible() throws Exception {
        JsonNode before = platformChannel();
        assertThat(before.get("verdict").asString())
                .as("种之前就已经是这个结论 —— 那后面那句断言证明不了是我种的这条让它变的")
                .isNotEqualTo("DISPATCHER_STALLED");

        Long id = seed("SKU_UPSERTED", 0, LocalDateTime.now().minusHours(6), null);
        try {
            JsonNode r = platformChannel();
            assertThat(r.get("verdict").asString())
                    .as("躺了六小时、一次都没被碰过 —— 这是投递任务停了，不是「正在处理」"
                            + "（种之前 retrying=%s；若非 0 则会先落到 CONSUMER_FAILING）",
                            before.get("retrying"))
                    .isEqualTo("DISPATCHER_STALLED");
            assertThat(r.get("pending").asLong()).isEqualTo(before.get("pending").asLong() + 1);
            assertThat(r.get("neverTried").asLong()).isEqualTo(before.get("neverTried").asLong() + 1);
            assertThat(r.get("oldestPendingAt").isNull())
                    .as("最老一条的时间是这一页的判据，不能为空 —— 「积压 1 条」自己说明不了任何事")
                    .isFalse();
        } finally {
            drop(id);
        }
    }

    @Test
    @DisplayName("★★★ 「没人碰过」与「一直在失败」必须分开 —— status 那一列对此一无所知")
    void neverTriedIsNotTheSameAsRetrying() throws Exception {
        JsonNode before = platformChannel();
        // 同样躺了六小时，唯一的差别是它被投过、失败过
        Long id = seed("SKU_UPSERTED", 3, LocalDateTime.now().minusHours(6), "NullPointerException: sku");
        try {
            JsonNode r = platformChannel();
            assertThat(r.get("verdict").asString())
                    .as("重试过 3 次 —— 投递任务显然在跑，坏的是消费者；说成「投递停了」会去查错东西")
                    .isEqualTo("CONSUMER_FAILING");
            assertThat(r.get("retrying").asLong()).isEqualTo(before.get("retrying").asLong() + 1);
            assertThat(r.get("neverTried").asLong())
                    .as("这一条是重试过的，不该被算进「没人碰过」")
                    .isEqualTo(before.get("neverTried").asLong());
            assertThat(r.get("maxRetry").asLong()).isGreaterThanOrEqualTo(3L);
            assertThat(r.get("lastError").asString())
                    .as("消费者坏了却不给错误 —— 那这一档等于只说了「有问题」")
                    .contains("NullPointerException");
        } finally {
            drop(id);
        }
    }

    @Test
    @DisplayName("★★ 新鲜的积压是 BACKLOG 不是故障 —— 投递每 5 秒一轮，一条在途是常态")
    void freshBacklogIsNotAFault() throws Exception {
        JsonNode before = platformChannel();
        Long id = seed("SKU_UPSERTED", 0, LocalDateTime.now(), null);
        try {
            JsonNode r = platformChannel();
            /*
             * 一条**新鲜**的积压不该改变结论：本来 OK 的变成 BACKLOG（有东西在途），
             * 本来就有问题的仍然是那个问题。判成故障的话这一页会天天误报，
             * 而一个天天误报的告警页等于没有。
             */
            String expected = "OK".equals(before.get("verdict").asString())
                    ? "BACKLOG" : before.get("verdict").asString();
            assertThat(r.get("verdict").asString())
                    .as("刚产生的事件把结论从 %s 改成了 %s", before.get("verdict"), r.get("verdict"))
                    .isEqualTo(expected);
            assertThat(r.get("pending").asLong()).isEqualTo(before.get("pending").asLong() + 1);
        } finally {
            drop(id);
        }
    }

    private Long seed(String type, int retry, LocalDateTime createdAt, String lastError) {
        SysOutbox e = new SysOutbox();
        e.setEventNo("LINKPROBE-" + System.nanoTime());
        e.setAggregateType("SKU");
        e.setAggregateId("PROBE");
        e.setEventType(type);
        e.setPayload("{}");
        e.setStatus(SysOutbox.PENDING);
        e.setRetryCount(retry);
        e.setLastError(lastError);
        e.setCreatedAt(createdAt);
        outbox.insert(e);
        return e.getId();
    }

    /** 种子必须还原：留着它，别的用例里这条链路凭空「断」着 */
    private void drop(Long id) {
        outbox.deleteById(id);
    }
}

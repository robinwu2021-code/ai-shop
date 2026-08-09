package ai.neargo.shop.e2e;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>J3：驳回与重来 —— 失败路径。</b>
 *
 * <p>这条旅程比 J1 更值得写：**成功路径谁都会走一遍，失败路径没人会手工试第二次**。
 * 而真实世界里，第一次就通过的申请是少数 —— 缺执照、没填范围、填错联系人
 * 才是运营每天在处理的。
 *
 * <p>它同时锁住三条本轮修过或差点漏掉的规则：
 * <ul>
 *   <li>服务范围为空时**通过要被拒**（不能产出一个对谁都不可见的隐形商家）</li>
 *   <li>驳回**必须写理由**（不写等于让人猜着改，而重来的人有一部分不会回来）</li>
 *   <li>第二张执照建出**第二个主体**（曾经按人判幂等，会把第一个主体改掉且不报错）</li>
 * </ul>
 */
@DisplayName("J3 · 驳回与重来（失败路径）")
class J3ApplyRejectAndRetryE2eTest extends E2eBase {

    @BeforeEach
    void setUp() {
        resetDatabaseOnce();
    }

    @Test
    @DisplayName("不填范围 → 拒绝通过 → 驳回必写理由 → 重提 → 通过 → 第二张执照")
    void rejectThenRetryThenSecondLicense() {
        String phone = nextPhone();
        String userToken = loginConsumer(phone);
        String ops = loginOps("admin", "admin123");

        // ── 1. 提交申请，**不填服务范围**（ADR-009 允许留空）───────────
        String applyNo = post("/mp/merchant/apply", userToken, applyBody("J3 无范围店", null))
                .get("applyNo").asString();
        step(1, "提交申请（无服务范围）", applyNo);

        // ── 2. 运营不补范围直接通过 → 必须被拒 ────────────────────────
        /*
         * 放过去的后果不是报错，是**产出一个隐形商家**：
         * 他上着架、等着单，而 C 端任何人都搜不到他，
         * 商家和运营都查不出原因（ADR-009）。
         */
        int code = expectFail("POST", "/ops/merchant/apply/" + applyNo + "/audit", ops,
                Map.of("approved", true));
        step(2, "不补范围直接通过 → 被拒", code);

        // ── 3. 驳回但不写理由 → 也要被拒 ─────────────────────────────
        int noReason = expectFail("POST", "/ops/merchant/apply/" + applyNo + "/audit", ops,
                Map.of("approved", false));
        step(3, "驳回不写理由 → 被拒", noReason);

        // ── 4. 带理由驳回，商家侧要能看到理由 ────────────────────────
        String reason = "缺少经营场所照片，请补充后重提";
        post("/ops/merchant/apply/" + applyNo + "/audit", ops,
                Map.of("approved", false, "reason", reason));
        JsonNode mine = get("/mp/merchant/apply", userToken);
        step(4, "已驳回", mine.get("status").asString());
        assertThat(mine.get("status").asString()).isEqualTo("REJECTED");
        assertThat(mine.get("rejectReason").asString())
                .as("驳回理由必须回到商家手上 —— 不给理由他只能反复重提同一份资料")
                .isEqualTo(reason);

        // ── 5. 商家补资料重提：终态释放了「一人一份进行中」的名额 ─────
        String retryNo = post("/mp/merchant/apply", userToken,
                applyBody("J3 补交后重提店", List.of("CM001"))).get("applyNo").asString();
        step(5, "重提", retryNo);
        assertThat(retryNo).as("重提应当是一份新的申请单").isNotEqualTo(applyNo);

        // ── 6. 通过 → C 端真的可见 ───────────────────────────────────
        post("/ops/merchant/apply/" + retryNo + "/audit", ops, Map.of("approved", true));
        JsonNode visible = get("/mp/merchant?communityNo=CM001&size=100", null);
        step(6, "C 端商家数", visible.get("total").asInt());
        assertThat(names(visible.get("records")))
                .as("通过后必须在 C 端按社区查得到 —— 查不到说明范围没配上")
                .contains("J3 补交后重提店");

        String firstEntity = get("/mp/merchant/apply", userToken).get("merchantNo").asString();

        // ── 7. 同一个人的第二张执照：必须建出第二个主体 ──────────────
        /*
         * 曾经的缺陷：幂等判据按「这个人有没有主体」，于是第二张执照通过时
         * 被当成重复点击，去改第一个主体 —— 两家店变一家，**全程无报错**。
         */
        String secondNo = post("/mp/merchant/apply", userToken,
                applyBody("J3 第二张执照店", List.of("CM001"))).get("applyNo").asString();
        post("/ops/merchant/apply/" + secondNo + "/audit", ops, Map.of("approved", true));
        String secondEntity = get("/ops/merchant/apply/search?status=APPROVED&size=100", ops)
                .get("records").valueStream()
                .filter(n -> "J3 第二张执照店".equals(n.get("name").asString()))
                .map(n -> n.get("merchantNo").asString())
                .findFirst().orElseThrow(() -> new AssertionError("第二张执照的申请单没找到"));
        step(7, "两个主体", firstEntity + " / " + secondEntity);

        assertThat(secondEntity)
                .as("第二张执照必须是新主体 —— 否则第一家店的名称/行业会被悄悄覆盖")
                .isNotEqualTo(firstEntity);
        JsonNode after = get("/mp/merchant?communityNo=CM001&size=100", null);
        assertThat(names(after.get("records")))
                .as("两家店都要在 —— 第一家不能因为第二张执照而消失")
                .contains("J3 补交后重提店", "J3 第二张执照店");
    }

    // ------------------------------------------------------------------ 辅助

    private LinkedHashMap<String, Object> applyBody(String name, List<String> communityNos) {
        var body = new LinkedHashMap<String, Object>();
        body.put("name", name);
        body.put("subject", "MICRO");
        body.put("industry", "RETAIL");
        body.put("contactName", "J3 老板");
        body.put("contactPhone", "13500000000");
        body.put("category", "日用");
        body.put("desc", "J3 旅程用");
        if (communityNos != null) {
            body.put("serviceScope", "COMMUNITY");
            body.put("communityNos", communityNos);
        }
        return body;
    }

    private List<String> names(JsonNode records) {
        return records.valueStream().map(n -> n.get("name").asString()).toList();
    }
}

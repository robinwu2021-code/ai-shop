package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.support.TestLogin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * App 外收款记录（TDD-商户App外部记账 §5）：商家自报的流水，不进支付台账、不能退款、不改订单状态。
 *
 * <p>夹具：入驻审核通过的主体改成台湾市场（TWD，小数位 2，只收整元），再给默认门店配一个店长、一个店员。
 */
@SpringBootTest
@ActiveProfiles("test")
class BizOfflineRecordFlowTest {

    private static final String PATH = "/biz/payment/offline-records";
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @Autowired private OtpStore otpStore;
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private record Shop(String owner, String manager, String clerk, String storeNo, String entityNo) {
    }

    // ------------------------------------------------------------------ AC1 记一笔

    @Test
    @DisplayName("★★★ AC1 店员记一笔现金：返回整条记录（RECORDED、版本 1、CREATE 留痕、记录人）")
    void clerk_records_cash_receipt_and_gets_full_record() throws Exception {
        Shop s = shop();
        JsonNode r = data(record(s.clerk(), s, key(), body(35000, "TWD", "CASH", null, "早餐 3 份", null)));
        assertThat(r.get("recordNo").asString()).startsWith("OFR");
        assertThat(r.get("amountMinor").asLong()).isEqualTo(35000);
        assertThat(r.get("currency").asString()).isEqualTo("TWD");
        assertThat(r.get("method").asString()).isEqualTo("CASH");
        assertThat(r.get("note").asString()).isEqualTo("早餐 3 份");
        assertThat(r.get("status").asString()).isEqualTo("RECORDED");
        assertThat(r.get("corrected").asBoolean()).isFalse();
        assertThat(r.get("version").asLong()).isEqualTo(1);
        assertThat(r.get("recordedBy").asString()).isNotBlank();
        assertThat(r.get("recordedAt").asString()).endsWith("+08:00");
        assertThat(r.get("receivedAt").asString()).endsWith("+08:00");
        JsonNode h = r.get("history");
        assertThat(h.size()).isEqualTo(1);
        assertThat(h.get(0).get("action").asString()).isEqualTo("CREATE");
        assertThat(h.get(0).get("toAmountMinor").asLong()).isEqualTo(35000);
        assertThat(h.get(0).get("toMethod").asString()).isEqualTo("CASH");
        assertThat(h.get(0).get("fromAmountMinor").isNull()).isTrue();
    }

    @Test
    @DisplayName("★★ 记录人名称取店员在本店的显示名；没设过显示名、昵称又是默认「微信用户」时给 null（2026-11-09 166 联调）")
    void recorded_by_name_is_staff_display_name_not_default_nickname() throws Exception {
        Shop s = shop();
        JsonNode r = data(record(s.clerk(), s, key(), body(1000, "TWD", "CASH", null, null, null)));
        String userNo = r.get("recordedBy").asString();
        jdbc.update("UPDATE usr_account SET nickname='微信用户' WHERE user_no=?", userNo);
        jdbc.update("UPDATE mch_account SET display_name=NULL WHERE (user_no=? OR mch_account_no=?) AND entity_no=?",
                userNo, userNo, s.entityNo());
        JsonNode unnamed = data(call(s.clerk(), s, get("/biz/payment/offline-records")));
        assertThat(unnamed.get("items").get(0).get("recordedByName").isNull()).isTrue();
        assertThat(unnamed.get("items").get(0).get("history").get(0).get("byName").isNull()).isTrue();

        jdbc.update("UPDATE mch_account SET display_name='阿明' WHERE (user_no=? OR mch_account_no=?) AND entity_no=?",
                userNo, userNo, s.entityNo());
        JsonNode named = data(call(s.clerk(), s, get("/biz/payment/offline-records")));
        assertThat(named.get("items").get(0).get("recordedByName").asString()).isEqualTo("阿明");
        assertThat(named.get("items").get(0).get("history").get(0).get("byName").asString()).isEqualTo("阿明");
    }

    @Test
    @DisplayName("★★ /biz/context 的 actorNo 与记录的 recordedBy 同值：店主、店员都能认出「我记的」（2026-11-09 166 联调）")
    void context_actor_no_matches_recorded_by_for_owner_and_clerk() throws Exception {
        Shop s = shop();
        for (String token : new String[]{s.owner(), s.clerk()}) {
            String recordedBy = data(record(token, s, key(), body(1000, "TWD", "CASH", null, null, null)))
                    .get("recordedBy").asString();
            JsonNode ctx = data(call(token, s, get("/biz/context")));
            assertThat(ctx.get("actorNo").asString()).as("actorNo").isNotBlank().isEqualTo(recordedBy);
        }
    }

    @Test
    @DisplayName("★★ AC1/AC10 币种必须是门店市场的币种：台湾店记 CNY → 10400")
    void record_rejects_currency_other_than_store_market() throws Exception {
        Shop s = shop();
        assertThat(code(record(s.owner(), s, key(), body(48000, "CNY", "CASH", null, null, null)))).isEqualTo(10400);
        assertThat(code(record(s.owner(), s, key(), body(48000, "twd", "CASH", null, null, null)))).isEqualTo(10400);
    }

    @Test
    @DisplayName("★★ AC1 未知字段（含旧字段 requestId、storeNo）与非正金额、未知方式、缺幂等键 → 10400")
    void record_rejects_unknown_field_and_non_positive_amount() throws Exception {
        Shop s = shop();
        assertThat(code(record(s.owner(), s, key(),
                "{\"amountMinor\":48000,\"currency\":\"TWD\",\"method\":\"CASH\",\"requestId\":\"r1\"}")))
                .isEqualTo(10400);
        assertThat(code(record(s.owner(), s, key(),
                "{\"amountMinor\":48000,\"currency\":\"TWD\",\"method\":\"CASH\",\"storeNo\":\"" + s.storeNo() + "\"}")))
                .isEqualTo(10400);
        assertThat(code(record(s.owner(), s, key(), body(0, "TWD", "CASH", null, null, null)))).isEqualTo(10400);
        assertThat(code(record(s.owner(), s, key(), body(-100, "TWD", "CASH", null, null, null)))).isEqualTo(10400);
        assertThat(code(record(s.owner(), s, key(), body(100, "TWD", "POS", null, null, null)))).isEqualTo(10400);
        assertThat(code(record(s.owner(), s, key(), body(100, "TWD", "CASH", null, "x".repeat(101), null))))
                .isEqualTo(10400);
        assertThat(code(record(s.owner(), s, null, body(100, "TWD", "CASH", null, null, null)))).isEqualTo(10400);
        assertThat(count(s)).isZero();
    }

    @Test
    @DisplayName("★★ AC1 实收时间不得晚于现在 +2 分钟，不得早于门店时区「今天 − 31 天」00:00")
    void record_rejects_received_at_in_future_or_older_than_31_days() throws Exception {
        Shop s = shop();
        String future = OffsetDateTime.now(TAIPEI).plusMinutes(5).toString();
        assertThat(code(record(s.owner(), s, key(), body(100, "TWD", "CASH", null, null, future)))).isEqualTo(10400);
        String old = LocalDate.now(TAIPEI).minusDays(32).atStartOfDay(TAIPEI).toOffsetDateTime().toString();
        assertThat(code(record(s.owner(), s, key(), body(100, "TWD", "CASH", null, null, old)))).isEqualTo(10400);
        String yesterday = OffsetDateTime.now(TAIPEI).minusDays(1).truncatedTo(ChronoUnit.SECONDS).toString();
        JsonNode r = data(record(s.owner(), s, key(), body(100, "TWD", "CASH", null, null, yesterday)));
        assertThat(OffsetDateTime.parse(r.get("receivedAt").asString()).toInstant())
                .isEqualTo(OffsetDateTime.parse(yesterday).toInstant());
    }

    @Test
    @DisplayName("★★ AC1 下限按门店市场时区算：台北「今天 − 31 天」00:00 正好收，早 1 秒拒")
    void received_at_lower_bound_uses_store_market_zone() throws Exception {
        Shop s = shop();
        OffsetDateTime bound = LocalDate.now(TAIPEI).minusDays(31).atStartOfDay(TAIPEI).toOffsetDateTime();
        assertThat(code(record(s.owner(), s, key(), body(100, "TWD", "CASH", null, null, bound.toString()))))
                .isZero();
        assertThat(code(record(s.owner(), s, key(),
                body(100, "TWD", "CASH", null, null, bound.minusSeconds(1).toString())))).isEqualTo(10400);
    }

    @Test
    @DisplayName("★★ AC1 orderNo 只认本店子单号：别店的单、主单号、不存在的号 → 10404；本店子单号 → 成功")
    void record_with_order_of_another_store_is_not_found() throws Exception {
        Shop s = shop();
        Shop other = shop();
        String mine = subOrder(s, "WAIT_OFFLINE_PAY");
        String theirs = subOrder(other, "WAIT_OFFLINE_PAY");
        String mainOrderNo = jdbc.queryForObject("SELECT order_no FROM ord_sub_order WHERE sub_order_no=?",
                String.class, mine);
        assertThat(code(record(s.owner(), s, key(), body(100, "TWD", "CASH", theirs, null, null)))).isEqualTo(10404);
        assertThat(code(record(s.owner(), s, key(), body(100, "TWD", "CASH", mainOrderNo, null, null))))
                .isEqualTo(10404);
        assertThat(code(record(s.owner(), s, key(), body(100, "TWD", "CASH", "SUB-NOPE", null, null))))
                .isEqualTo(10404);
        JsonNode ok = data(record(s.owner(), s, key(), body(100, "TWD", "CASH", mine, null, null)));
        assertThat(ok.get("orderNo").asString()).isEqualTo(mine);
    }

    // ------------------------------------------------------------------ AC2 幂等

    @Test
    @DisplayName("★★★ AC2 同一幂等键、同一请求体重送：返回第一次那条，不新建")
    void same_idempotency_key_same_body_returns_first_record() throws Exception {
        Shop s = shop();
        String k = key();
        String b = body(48000, "TWD", "CASH", null, "午餐", null);
        JsonNode first = data(record(s.clerk(), s, k, b));
        JsonNode again = data(record(s.clerk(), s, k, b));
        assertThat(again.get("recordNo").asString()).isEqualTo(first.get("recordNo").asString());
        assertThat(count(s)).isEqualTo(1);
    }

    @Test
    @DisplayName("★★★ AC2 同一幂等键、请求体不同 → 10409，不新建")
    void same_idempotency_key_different_body_conflicts() throws Exception {
        Shop s = shop();
        String k = key();
        data(record(s.clerk(), s, k, body(48000, "TWD", "CASH", null, null, null)));
        assertThat(code(record(s.clerk(), s, k, body(49000, "TWD", "CASH", null, null, null)))).isEqualTo(10409);
        assertThat(count(s)).isEqualTo(1);
    }

    @Test
    @DisplayName("★★ AC2 同一幂等键并发双写：只落一行，几路都拿到同一条")
    void concurrent_same_key_creates_one_row() throws Exception {
        Shop s = shop();
        String k = key();
        String b = body(48000, "TWD", "CASH", null, null, null);
        int n = 4;
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<JsonNode>> all = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<JsonNode> call = () -> {
                    start.await();
                    return json.readTree(record(s.clerk(), s, k, b));
                };
                all.add(pool.submit(call));
            }
            start.countDown();
            Set<String> nos = new HashSet<>();
            for (var f : all) {
                JsonNode r = f.get();
                assertThat(r.get("code").asInt()).as(r.toString()).isZero();
                nos.add(r.get("data").get("recordNo").asString());
            }
            assertThat(nos).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count(s)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ AC3 不进支付域

    @Test
    @DisplayName("★★★ AC3 外部记录不进 /biz/payment/orders、summary、statement，也不写任何支付表")
    void offline_record_absent_from_payment_orders_summary_and_statement() throws Exception {
        Shop s = shop();
        long payOrders = jdbc.queryForObject("SELECT COUNT(*) FROM pay_order", Long.class);
        long refunds = jdbc.queryForObject("SELECT COUNT(*) FROM pay_refund", Long.class);
        String no = data(record(s.owner(), s, key(), body(48000, "TWD", "CASH", null, null, null)))
                .get("recordNo").asString();
        String today = LocalDate.now(TAIPEI).toString();
        for (String p : List.of("/biz/payment/orders?from=" + today + "&to=" + today,
                "/biz/payment/orders/summary?from=" + today + "&to=" + today, "/biz/payment/statement")) {
            assertThat(call(s.owner(), s, get(p))).as(p).doesNotContain(no);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_order", Long.class)).isEqualTo(payOrders);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_refund", Long.class)).isEqualTo(refunds);
    }

    @Test
    @DisplayName("★★ AC3 拿外部记录号去退款：查不到，不产生退款")
    void refund_by_offline_record_no_is_not_found() throws Exception {
        Shop s = shop();
        String no = data(record(s.owner(), s, key(), body(48000, "TWD", "CASH", null, null, null)))
                .get("recordNo").asString();
        long refunds = jdbc.queryForObject("SELECT COUNT(*) FROM pay_refund", Long.class);
        String r = call(s.owner(), s, post("/biz/payment/refund").contentType(MediaType.APPLICATION_JSON)
                .content("{\"payOrderNo\":\"" + no + "\",\"amountMinor\":48000,\"reason\":\"x\"}"));
        // 支付域自己的「支付单不存在」（70083）：按 payOrderNo 查不到，OFR 号与支付单号空间不重叠
        assertThat(json.readTree(r).get("code").asInt()).as(r).isEqualTo(70083);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_refund", Long.class)).isEqualTo(refunds);
    }

    @Test
    @DisplayName("★★ AC3 带了订单号也不推订单：待线下付款的子单记完仍是 WAIT_OFFLINE_PAY")
    void record_with_order_no_leaves_order_status_unchanged() throws Exception {
        Shop s = shop();
        String sub = subOrder(s, "WAIT_OFFLINE_PAY");
        data(record(s.owner(), s, key(), body(48000, "TWD", "CASH", sub, null, null)));
        assertThat(jdbc.queryForObject("SELECT status FROM ord_sub_order WHERE sub_order_no=?", String.class, sub))
                .isEqualTo("WAIT_OFFLINE_PAY");
    }

    // ------------------------------------------------------------------ AC4 列表

    @Test
    @DisplayName("★★★ AC4 列表只看本店、按实收时间新的在前、游标翻页")
    void list_shows_only_current_store_newest_first_with_cursor() throws Exception {
        Shop s = shop();
        Shop other = shop();
        data(record(other.owner(), other, key(), body(100, "TWD", "CASH", null, null, null)));
        List<String> nos = new ArrayList<>();
        for (int i = 3; i >= 1; i--) {
            String at = OffsetDateTime.now(TAIPEI).minusHours(i).truncatedTo(ChronoUnit.SECONDS).toString();
            nos.add(0, data(record(s.clerk(), s, key(), body(100L * i, "TWD", "CASH", null, null, at)))
                    .get("recordNo").asString());
        }
        String from = LocalDate.now(TAIPEI).minusDays(1).toString();
        JsonNode page1 = data(json(call(s.clerk(), s, get(PATH + "?size=2&from=" + from))));
        assertThat(page1.get("items").size()).isEqualTo(2);
        assertThat(page1.get("nextCursor").isNull()).isFalse();
        JsonNode page2 = data(json(call(s.clerk(), s, get(PATH + "?size=2&from=" + from + "&cursor="
                + page1.get("nextCursor").asString()))));
        assertThat(page2.get("items").size()).isEqualTo(1);
        assertThat(page2.get("nextCursor").isNull()).isTrue();
        List<String> seen = new ArrayList<>();
        page1.get("items").forEach(r -> seen.add(r.get("recordNo").asString()));
        page2.get("items").forEach(r -> seen.add(r.get("recordNo").asString()));
        assertThat(seen).containsExactlyElementsOf(nos);
    }

    @Test
    @DisplayName("★★ AC4 按实收日期、方式、状态筛选")
    void list_filters_by_received_date_method_and_status() throws Exception {
        Shop s = shop();
        String twoDaysAgo = OffsetDateTime.now(TAIPEI).minusDays(2).truncatedTo(ChronoUnit.SECONDS).toString();
        String old = data(record(s.owner(), s, key(), body(100, "TWD", "CASH", null, null, twoDaysAgo)))
                .get("recordNo").asString();
        String cash = data(record(s.owner(), s, key(), body(200, "TWD", "CASH", null, null, null)))
                .get("recordNo").asString();
        JsonNode pos = data(record(s.owner(), s, key(), body(300, "TWD", "EXTERNAL_POS", null, null, null)));
        data(record(s.owner(), s, null, pos.get("recordNo").asString(), "void",
                "{\"reason\":\"记错了\",\"expectedVersion\":1}"));

        assertThat(nos(data(json(call(s.owner(), s, get(PATH)))))).containsExactlyInAnyOrder(cash,
                pos.get("recordNo").asString());
        String d = LocalDate.now(TAIPEI).minusDays(2).toString();
        assertThat(nos(data(json(call(s.owner(), s, get(PATH + "?from=" + d + "&to=" + d)))))).containsExactly(old);
        assertThat(nos(data(json(call(s.owner(), s, get(PATH + "?method=EXTERNAL_POS"))))))
                .containsExactly(pos.get("recordNo").asString());
        assertThat(nos(data(json(call(s.owner(), s, get(PATH + "?status=RECORDED")))))).containsExactly(cash);
        assertThat(nos(data(json(call(s.owner(), s, get(PATH + "?status=VOIDED"))))))
                .containsExactly(pos.get("recordNo").asString());
        assertThat(code(call(s.owner(), s, get(PATH + "?method=POS")))).isEqualTo(10400);
        assertThat(code(call(s.owner(), s, get(PATH + "?status=DELETED")))).isEqualTo(10400);
        assertThat(code(call(s.owner(), s, get(PATH + "?from=2026-13-01")))).isEqualTo(10400);
    }

    @Test
    @DisplayName("★★ AC4 跨度 to − from = 93 天放行，94 天 10400；to 早于 from 10400")
    void list_range_to_minus_from_93_ok_94_rejected() throws Exception {
        Shop s = shop();
        LocalDate to = LocalDate.now(TAIPEI);
        assertThat(code(call(s.owner(), s, get(PATH + "?from=" + to.minusDays(93) + "&to=" + to)))).isZero();
        assertThat(code(call(s.owner(), s, get(PATH + "?from=" + to.minusDays(94) + "&to=" + to)))).isEqualTo(10400);
        assertThat(code(call(s.owner(), s, get(PATH + "?from=" + to + "&to=" + to.minusDays(1))))).isEqualTo(10400);
    }

    @Test
    @DisplayName("★★ AC4 关键字 q 命中订单号或备注（中文可搜，% 不当通配）")
    void list_q_matches_order_no_or_note() throws Exception {
        Shop s = shop();
        String sub = subOrder(s, "WAIT_OFFLINE_PAY");
        String withOrder = data(record(s.owner(), s, key(), body(100, "TWD", "CASH", sub, null, null)))
                .get("recordNo").asString();
        String withNote = data(record(s.owner(), s, key(), body(100, "TWD", "CASH", null, "豆漿兩杯", null)))
                .get("recordNo").asString();
        data(record(s.owner(), s, key(), body(100, "TWD", "CASH", null, "別的", null)));
        assertThat(nos(data(json(call(s.owner(), s, get(PATH).param("q", "豆漿")))))).containsExactly(withNote);
        assertThat(nos(data(json(call(s.owner(), s, get(PATH).param("q", sub.substring(2))))))).containsExactly(withOrder);
        assertThat(nos(data(json(call(s.owner(), s, get(PATH).param("q", "%")))))).isEmpty();
        assertThat(code(call(s.owner(), s, get(PATH).param("q", "x".repeat(33))))).isEqualTo(10400);
    }

    @Test
    @DisplayName("★★ 已拍板 D3-D 列表按订单号精确筛：订单详情据此判断「已有外部記錄」")
    void list_filters_by_order_no() throws Exception {
        Shop s = shop();
        String sub = subOrder(s, "WAIT_OFFLINE_PAY");
        String no = data(record(s.owner(), s, key(), body(100, "TWD", "CASH", sub, null, null)))
                .get("recordNo").asString();
        data(record(s.owner(), s, key(), body(100, "TWD", "CASH", null, sub, null)));
        assertThat(nos(data(json(call(s.owner(), s, get(PATH).param("orderNo", sub)))))).containsExactly(no);
    }

    @Test
    @DisplayName("★★ 已拍板 D6-B 汇总：只算未作废的记录，单列外部记账金额与笔数")
    void summary_counts_recorded_only() throws Exception {
        Shop s = shop();
        data(record(s.owner(), s, key(), body(48000, "TWD", "CASH", null, null, null)));
        data(record(s.owner(), s, key(), body(12000, "TWD", "BANK_TRANSFER", null, null, null)));
        String voided = data(record(s.owner(), s, key(), body(5000, "TWD", "CASH", null, null, null)))
                .get("recordNo").asString();
        data(record(s.owner(), s, null, voided, "void", "{\"reason\":\"重复\",\"expectedVersion\":1}"));
        JsonNode sum = data(json(call(s.clerk(), s, get(PATH + "/summary"))));
        assertThat(sum.get("currency").asString()).isEqualTo("TWD");
        assertThat(sum.get("count").asLong()).isEqualTo(2);
        assertThat(sum.get("amountMinor").asLong()).isEqualTo(60000);
        assertThat(sum.get("from").asString()).isEqualTo(LocalDate.now(TAIPEI).toString());
        assertThat(code(call(s.owner(), s, get(PATH + "/summary?from=" + LocalDate.now(TAIPEI).minusDays(94)))))
                .isEqualTo(10400);
    }

    // ------------------------------------------------------------------ AC5 / AC6 更正与作废

    @Test
    @DisplayName("★★★ AC5 店长更正金额、方式与实收时间：记录换新值，留痕带旧值、新值、原因、操作人")
    void manager_corrects_amount_and_method_history_keeps_old_values() throws Exception {
        Shop s = shop();
        String at = OffsetDateTime.now(TAIPEI).minusHours(3).truncatedTo(ChronoUnit.SECONDS).toString();
        String no = data(record(s.clerk(), s, key(), body(48000, "TWD", "CASH", null, null, at)))
                .get("recordNo").asString();
        String newAt = OffsetDateTime.now(TAIPEI).minusDays(1).truncatedTo(ChronoUnit.SECONDS).toString();
        JsonNode r = data(record(s.manager(), s, null, no, "correct",
                "{\"amountMinor\":45000,\"currency\":\"TWD\",\"method\":\"EXTERNAL_POS\",\"reason\":\"刷卡不是现金\","
                        + "\"receivedAt\":\"" + newAt + "\",\"expectedVersion\":1}"));
        assertThat(r.get("amountMinor").asLong()).isEqualTo(45000);
        assertThat(r.get("method").asString()).isEqualTo("EXTERNAL_POS");
        assertThat(OffsetDateTime.parse(r.get("receivedAt").asString()).toInstant())
                .isEqualTo(OffsetDateTime.parse(newAt).toInstant());
        assertThat(r.get("corrected").asBoolean()).isTrue();
        assertThat(r.get("version").asLong()).isEqualTo(2);
        JsonNode c = r.get("history").get(1);
        assertThat(c.get("action").asString()).isEqualTo("CORRECT");
        assertThat(c.get("reason").asString()).isEqualTo("刷卡不是现金");
        assertThat(c.get("fromAmountMinor").asLong()).isEqualTo(48000);
        assertThat(c.get("toAmountMinor").asLong()).isEqualTo(45000);
        assertThat(c.get("fromMethod").asString()).isEqualTo("CASH");
        assertThat(c.get("toMethod").asString()).isEqualTo("EXTERNAL_POS");
        assertThat(OffsetDateTime.parse(c.get("fromReceivedAt").asString()).toInstant())
                .isEqualTo(OffsetDateTime.parse(at).toInstant());
        assertThat(OffsetDateTime.parse(c.get("toReceivedAt").asString()).toInstant())
                .isEqualTo(OffsetDateTime.parse(newAt).toInstant());
        assertThat(c.get("by").asString()).isNotEqualTo(r.get("history").get(0).get("by").asString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_offline_record_event WHERE record_no=?",
                Long.class, no)).isEqualTo(2);
    }

    @Test
    @DisplayName("★★ AC5 更正要原因、要真的改了东西、币种不能换")
    void correct_requires_reason_and_a_real_change() throws Exception {
        Shop s = shop();
        String no = data(record(s.owner(), s, key(), body(48000, "TWD", "CASH", null, null, null)))
                .get("recordNo").asString();
        assertThat(code(record(s.owner(), s, null, no, "correct",
                "{\"amountMinor\":45000,\"currency\":\"TWD\",\"method\":\"CASH\",\"expectedVersion\":1}")))
                .isEqualTo(10400);
        assertThat(code(record(s.owner(), s, null, no, "correct",
                "{\"amountMinor\":45000,\"currency\":\"TWD\",\"method\":\"CASH\",\"reason\":\" \",\"expectedVersion\":1}")))
                .isEqualTo(10400);
        assertThat(code(record(s.owner(), s, null, no, "correct",
                "{\"amountMinor\":48000,\"currency\":\"TWD\",\"method\":\"CASH\",\"reason\":\"没改\",\"expectedVersion\":1}")))
                .isEqualTo(10400);
        assertThat(code(record(s.owner(), s, null, no, "correct",
                "{\"amountMinor\":45000,\"currency\":\"CNY\",\"method\":\"CASH\",\"reason\":\"换币种\",\"expectedVersion\":1}")))
                .isEqualTo(10400);
        assertThat(code(record(s.owner(), s, null, no, "correct",
                "{\"amountMinor\":45000,\"currency\":\"TWD\",\"method\":\"CASH\",\"reason\":\"x\"}")))
                .isEqualTo(10400);
    }

    @Test
    @DisplayName("★★★ AC6 店长作废：状态 VOIDED，行不删，留痕追加 VOID")
    void manager_voids_record_row_kept_with_void_event() throws Exception {
        Shop s = shop();
        String no = data(record(s.clerk(), s, key(), body(48000, "TWD", "CASH", null, null, null)))
                .get("recordNo").asString();
        JsonNode r = data(record(s.manager(), s, null, no, "void", "{\"reason\":\"重复记了\",\"expectedVersion\":1}"));
        assertThat(r.get("status").asString()).isEqualTo("VOIDED");
        assertThat(r.get("version").asLong()).isEqualTo(2);
        assertThat(r.get("amountMinor").asLong()).isEqualTo(48000);
        JsonNode v = r.get("history").get(1);
        assertThat(v.get("action").asString()).isEqualTo("VOID");
        assertThat(v.get("reason").asString()).isEqualTo("重复记了");
        assertThat(jdbc.queryForObject("SELECT status FROM ord_offline_record WHERE record_no=? AND deleted=0",
                String.class, no)).isEqualTo("VOIDED");
        assertThat(code(record(s.manager(), s, null, no, "void", "{\"expectedVersion\":2}"))).isEqualTo(10400);
    }

    // ------------------------------------------------------------------ AC7 状态与范围

    @Test
    @DisplayName("★★★ AC7 已作废的再更正或作废 → 10409")
    void correct_or_void_after_void_conflicts() throws Exception {
        Shop s = shop();
        String no = data(record(s.owner(), s, key(), body(48000, "TWD", "CASH", null, null, null)))
                .get("recordNo").asString();
        data(record(s.owner(), s, null, no, "void", "{\"reason\":\"作废\",\"expectedVersion\":1}"));
        assertThat(code(record(s.owner(), s, null, no, "void", "{\"reason\":\"再废\",\"expectedVersion\":2}")))
                .isEqualTo(10409);
        assertThat(code(record(s.owner(), s, null, no, "correct",
                "{\"amountMinor\":45000,\"currency\":\"TWD\",\"method\":\"CASH\",\"reason\":\"改\",\"expectedVersion\":2}")))
                .isEqualTo(10409);
    }

    @Test
    @DisplayName("★★★ AC7 expectedVersion 与当前不符 → 10409，后到的不会盖掉先到的")
    void stale_expected_version_conflicts() throws Exception {
        Shop s = shop();
        String no = data(record(s.owner(), s, key(), body(48000, "TWD", "CASH", null, null, null)))
                .get("recordNo").asString();
        data(record(s.owner(), s, null, no, "correct",
                "{\"amountMinor\":45000,\"currency\":\"TWD\",\"method\":\"CASH\",\"reason\":\"先到\",\"expectedVersion\":1}"));
        assertThat(code(record(s.manager(), s, null, no, "correct",
                "{\"amountMinor\":40000,\"currency\":\"TWD\",\"method\":\"CASH\",\"reason\":\"后到\",\"expectedVersion\":1}")))
                .isEqualTo(10409);
        assertThat(code(record(s.manager(), s, null, no, "void", "{\"reason\":\"后到\",\"expectedVersion\":1}")))
                .isEqualTo(10409);
        assertThat(jdbc.queryForObject("SELECT amount_minor FROM ord_offline_record WHERE record_no=?", Long.class, no))
                .isEqualTo(45000);
    }

    @Test
    @DisplayName("★★ AC7 不存在或别店的记录：更正、作废都 10404，列表里也看不到")
    void record_of_another_store_is_not_found() throws Exception {
        Shop s = shop();
        Shop other = shop();
        String theirs = data(record(other.owner(), other, key(), body(100, "TWD", "CASH", null, null, null)))
                .get("recordNo").asString();
        assertThat(code(record(s.owner(), s, null, theirs, "void", "{\"reason\":\"x\",\"expectedVersion\":1}")))
                .isEqualTo(10404);
        assertThat(code(record(s.owner(), s, null, theirs, "correct",
                "{\"amountMinor\":200,\"currency\":\"TWD\",\"method\":\"CASH\",\"reason\":\"x\",\"expectedVersion\":1}")))
                .isEqualTo(10404);
        assertThat(code(record(s.owner(), s, null, "OFR-NOPE", "void", "{\"reason\":\"x\",\"expectedVersion\":1}")))
                .isEqualTo(10404);
        assertThat(nos(data(json(call(s.owner(), s, get(PATH)))))).doesNotContain(theirs);
    }

    @Test
    @DisplayName("★★ 已拍板 D7-C 记账超过 31 天（门店时区）的记录不能再更正或作废 → 10409")
    void correct_or_void_outside_31_day_window_conflicts() throws Exception {
        Shop s = shop();
        String no = data(record(s.owner(), s, key(), body(48000, "TWD", "CASH", null, null, null)))
                .get("recordNo").asString();
        jdbc.update("UPDATE ord_offline_record SET created_at=? WHERE record_no=?",
                Timestamp.from(Instant.now().minus(40, ChronoUnit.DAYS)), no);
        assertThat(code(record(s.owner(), s, null, no, "void", "{\"reason\":\"太久\",\"expectedVersion\":1}")))
                .isEqualTo(10409);
        assertThat(code(record(s.owner(), s, null, no, "correct",
                "{\"amountMinor\":45000,\"currency\":\"TWD\",\"method\":\"CASH\",\"reason\":\"太久\",\"expectedVersion\":1}")))
                .isEqualTo(10409);
    }

    // ------------------------------------------------------------------ AC8 角色

    @Test
    @DisplayName("★★★ AC8 店员能记不能改、不能作废；店长两样都能；店员、店长模板都能看")
    void clerk_cannot_correct_or_void() throws Exception {
        Shop s = shop();
        String no = data(record(s.clerk(), s, key(), body(48000, "TWD", "CASH", null, null, null)))
                .get("recordNo").asString();
        assertThat(code(record(s.clerk(), s, null, no, "correct",
                "{\"amountMinor\":45000,\"currency\":\"TWD\",\"method\":\"CASH\",\"reason\":\"x\",\"expectedVersion\":1}")))
                .isEqualTo(70006);
        assertThat(code(record(s.clerk(), s, null, no, "void", "{\"reason\":\"x\",\"expectedVersion\":1}")))
                .isEqualTo(70006);
        assertThat(BizPerms.can(Set.of(BizPerms.CLERK), BizPerms.PAY_OFFLINE_RECORD)).isTrue();
        assertThat(BizPerms.can(Set.of(BizPerms.CLERK), BizPerms.PAY_OFFLINE_MANAGE)).isFalse();
        assertThat(BizPerms.can(Set.of(BizPerms.MANAGER), BizPerms.PAY_OFFLINE_RECORD)).isTrue();
        assertThat(BizPerms.can(Set.of(BizPerms.MANAGER), BizPerms.PAY_OFFLINE_MANAGE)).isTrue();
        assertThat(BizPerms.can(Set.of(BizPerms.CLERK), BizPerms.PAY_ORDER_VIEW)).isTrue();
        assertThat(code(record(s.manager(), s, null, no, "void", "{\"reason\":\"x\",\"expectedVersion\":1}"))).isZero();
    }

    // ------------------------------------------------------------------ AC10 金额

    @Test
    @DisplayName("★★★ AC10 台湾店 NT$480 存 48000（TWD 小数位 2）")
    void twd_store_records_480_dollars_as_48000_minor() throws Exception {
        Shop s = shop();
        JsonNode r = data(record(s.owner(), s, key(), body(48000, "TWD", "CASH", null, null, null)));
        assertThat(jdbc.queryForObject("SELECT amount_minor FROM ord_offline_record WHERE record_no=?", Long.class,
                r.get("recordNo").asString())).isEqualTo(48000);
        assertThat(jdbc.queryForObject("SELECT currency_scale FROM sys_market WHERE market='TW'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("★★★ AC10 TWD 只收整元：48050 记账、更正都 10400")
    void twd_amount_not_whole_dollar_rejected_on_record_and_correct() throws Exception {
        Shop s = shop();
        assertThat(code(record(s.owner(), s, key(), body(48050, "TWD", "CASH", null, null, null)))).isEqualTo(10400);
        String no = data(record(s.owner(), s, key(), body(48000, "TWD", "CASH", null, null, null)))
                .get("recordNo").asString();
        assertThat(code(record(s.owner(), s, null, no, "correct",
                "{\"amountMinor\":48050,\"currency\":\"TWD\",\"method\":\"CASH\",\"reason\":\"零头\",\"expectedVersion\":1}")))
                .isEqualTo(10400);
    }

    // ------------------------------------------------------------------ 夹具

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static String body(long amount, String currency, String method, String orderNo, String note,
            String receivedAt) {
        StringBuilder b = new StringBuilder("{\"amountMinor\":").append(amount)
                .append(",\"currency\":\"").append(currency).append("\",\"method\":\"").append(method).append('"');
        if (orderNo != null) {
            b.append(",\"orderNo\":\"").append(orderNo).append('"');
        }
        if (note != null) {
            b.append(",\"note\":\"").append(note).append('"');
        }
        if (receivedAt != null) {
            b.append(",\"receivedAt\":\"").append(receivedAt).append('"');
        }
        return b.append('}').toString();
    }

    private String record(String token, Shop s, String key, String body) throws Exception {
        MockHttpServletRequestBuilder b = post(PATH).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        return call(token, s, b);
    }

    private String record(String token, Shop s, String key, String recordNo, String action, String body)
            throws Exception {
        return call(token, s, post(PATH + "/" + recordNo + "/" + action)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String call(String token, Shop s, MockHttpServletRequestBuilder b) throws Exception {
        return mvc().perform(b.header("Authorization", "Bearer " + token).header("X-Store-No", s.storeNo()))
                .andReturn().getResponse().getContentAsString();
    }

    private JsonNode json(String body) {
        return json.readTree(body);
    }

    private JsonNode data(String body) {
        return data(json.readTree(body));
    }

    private JsonNode data(JsonNode n) {
        assertThat(n.get("code").asInt()).as(n.toString()).isZero();
        return n.get("data");
    }

    private int code(String body) {
        return json.readTree(body).get("code").asInt();
    }

    private static List<String> nos(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(r -> out.add(r.get("recordNo").asString()));
        return out;
    }

    private long count(Shop s) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ord_offline_record WHERE store_no=?", Long.class,
                s.storeNo());
    }

    /** 本店一张子单（订单视角 orderNo = 子单号）；只为验证「引用但不推」，不走下单链路 */
    private String subOrder(Shop s, String status) {
        String sub = "SUB" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        jdbc.update("INSERT INTO ord_sub_order (sub_order_no, order_no, user_no, entity_no, store_no, status,"
                        + " tenant_no, created_at, updated_at) VALUES (?, ?, 'U-OFFLINE-BUYER', ?, ?, ?, 'MAIN', NOW(), NOW())",
                sub, "SO" + sub.substring(3), s.entityNo(), s.storeNo(), status);
        return sub;
    }

    private static String phone() {
        return "138" + String.format("%08d", (long) (Math.random() * 1e8));
    }

    /** 入驻审核通过 → 主体改台湾市场 → 默认门店配店长、店员各一人 */
    private Shop shop() throws Exception {
        String phone = phone();
        String user = TestLogin.consumer(mvc(), json, otpStore, phone);
        String applyNo = data(mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"外部记账" + phone.substring(7) + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\",\"communityNos\":[\"CM001\"]}"))
                .andReturn().getResponse().getContentAsString()).get("applyNo").asString();
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                .header("Authorization", "Bearer " + TestLogin.admin(mvc(), json))
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        String owner = TestLogin.merchantOwner(mvc(), json, otpStore, phone);
        String storeNo = data(mvc().perform(get("/biz/store/list").header("Authorization", "Bearer " + owner))
                .andReturn().getResponse().getContentAsString()).get(0).get("storeNo").asString();
        String entityNo = jdbc.queryForObject("SELECT entity_no FROM mch_store WHERE store_no=?", String.class,
                storeNo);
        jdbc.update("UPDATE mch_entity SET market='TW' WHERE entity_no=?", entityNo);
        return new Shop(owner, staff(owner, storeNo, BizPerms.MANAGER), staff(owner, storeNo, BizPerms.CLERK),
                storeNo, entityNo);
    }

    private String staff(String owner, String storeNo, String role) throws Exception {
        String phone = phone();
        String accountNo = data(mvc().perform(post("/biz/staff").header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"loginPhone\":\"" + phone + "\"}"))
                .andReturn().getResponse().getContentAsString()).get("mchAccountNo").asString();
        data(mvc().perform(post("/biz/staff/" + accountNo + "/store").header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeNo\":\"" + storeNo + "\",\"role\":\"" + role + "\",\"granted\":true}"))
                .andReturn().getResponse().getContentAsString());
        return TestLogin.merchantStaff(mvc(), json, otpStore, phone);
    }
}

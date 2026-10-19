package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 5.4 行级数据底座（TDD-订单状态机 AC19 / AC21 / AC22）。 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("行级退款 · 数据底座")
class LineRefundDataFlowTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★★ AC19 下单即落行实付快照：Σ 各行 = 子单实付 − 运费；AC21 确认收货写完成时刻")
    void snapshotAndCompletedAt() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otpStore, "13400153001");
        addToCart(token, "G0002", "SK0003", 2);
        addToCart(token, "G0001", "SK0001", 1);
        String payNo = createAndPay(token, "line-snap");
        for (String sub : jdbc.queryForList("select sub_order_no from ord_sub_order where order_no = ?", String.class, payNo)) {
            Long goodsPaid = jdbc.queryForObject(
                    "select pay_amount - coalesce(freight_amount, 0) from ord_sub_order where sub_order_no = ?", Long.class, sub);
            Long sum = jdbc.queryForObject(
                    "select coalesce(sum(paid_share_minor), 0) from ord_item where sub_order_no = ?", Long.class, sub);
            assertThat(sum).as("子单 %s 的行实付快照没落或 Σ 不等", sub).isEqualTo(goodsPaid).isPositive();
        }

        // Completion requires physical fulfillment, so exercise a real express journey.
        try (var journey = new ai.neargo.shop.support.OrderJourneyFixture(context, "13400153002", "13400153003")) {
            var purchase = journey.purchase();
            assertThat(completedAt(purchase.subNo())).isNull();
            journey.prepare(purchase);
            journey.ship(purchase);
            journey.receive(purchase);
            assertThat(completedAt(purchase.subNo())).as("完成时刻没写 —— 售后期没有起算点").isNotNull();
        }
    }

    @Test
    @DisplayName("★★ AC21 / AC22 V404 的两条回填在 H2 上能执行，且结果对（存量完成时刻、存量已退款标 FULL）")
    void backfillStatementsWork() throws Exception {
        jdbc.update("insert into ord_sub_order (sub_order_no, order_no, user_no, entity_no, status, pay_amount, goods_amount, "
                + "version, deleted, created_at, updated_at) values ('BF-SUB-1', 'BF-PAY-1', 'U-BF', 'M0001', 'COMPLETED', 1000, 1000, 0, 0, now(), now())");
        jdbc.update("insert into ord_sub_order (sub_order_no, order_no, user_no, entity_no, status, pay_amount, goods_amount, "
                + "version, deleted, created_at, updated_at) values ('BF-SUB-2', 'BF-PAY-2', 'U-BF', 'M0001', 'REFUNDED', 2000, 2000, 0, 0, now(), now())");
        jdbc.update("insert into ord_status_log (sub_order_no, status, at, created_at) values ('BF-SUB-1', 'COMPLETED', 111, now())");
        jdbc.update("insert into ord_status_log (sub_order_no, status, at, created_at) values ('BF-SUB-1', 'COMPLETED', 222, now())");

        for (String stmt : backfillStatements()) {
            jdbc.update(stmt);
        }
        assertThat(completedAt("BF-SUB-1")).isEqualTo(222L);
        assertThat(jdbc.queryForObject("select refund_progress from ord_sub_order where sub_order_no = 'BF-SUB-2'", String.class))
                .isEqualTo("FULL");
        assertThat(jdbc.queryForObject("select refunded_minor from ord_sub_order where sub_order_no = 'BF-SUB-2'", Long.class))
                .as("没有售后单可取时按实付").isEqualTo(2000L);
    }

    // ---------------------------------------------------------------- helpers

    /** 从 V404 原文里取出 UPDATE 语句（测的就是要上线的那几句，不是测试里另抄一份） */
    private static List<String> backfillStatements() throws Exception {
        Path f = Path.of("src/main/resources/db/migration/V404__line_refund_ledger.sql");
        String sql = Files.readString(f).replaceAll("(?m)^--.*$", "");
        return Arrays.stream(sql.split(";")).map(String::trim).filter(s -> s.startsWith("UPDATE")).toList();
    }

    private Long completedAt(String sub) {
        return jdbc.queryForObject("select completed_at from ord_sub_order where sub_order_no = ?", Long.class, sub);
    }

    private void addToCart(String token, String goods, String sku, int qty) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"%s\",\"skuNo\":\"%s\",\"qty\":%d}".formatted(goods, sku, qty)))
                .andExpect(status().isOk());
    }

    private String createAndPay(String token, String key) throws Exception {
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
        String payNo = json.readTree(body).get("data").get("payOrderNo").asString();
        new ai.neargo.shop.support.CashReceiptFixture(context).pay(token, payNo);
        return payNo;
    }
}

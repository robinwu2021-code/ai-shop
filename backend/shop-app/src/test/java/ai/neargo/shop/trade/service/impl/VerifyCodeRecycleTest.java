package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 核销码号段用满之后，支付确认不能恒抛。
 *
 * <p>{@code uk_verify_code} 全表永久唯一，历史单的码一直占着号段：100 万个号发完，
 * 每次付款都连撞 8 次、抛 {@code IllegalStateException}，用户付了钱、子单停在待发码。
 * 这里锁住「终态且冷却期已过的旧单让出它的码」，同时活码与刚结束的单一个都不能让。
 */
@SpringBootTest
@ActiveProfiles("test")
class VerifyCodeRecycleTest {

    @Autowired private StoreOrderMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("★★★ 撞上已完成 40 天的旧单：码从旧单上摘下来复用，旧单其余不动")
    void finishedLongAgoGivesUpItsCode() {
        String code = code();
        String old = sub("COMPLETED", code, 40);

        assertThat(OrderServiceImpl.originalOwnerUnusedVerifyCode(mapper, codes(code))).isEqualTo(code);
        assertThat(jdbc.queryForObject("select verify_code from ord_sub_order where sub_order_no=?",
                String.class, old)).as("旧单让出码").isNull();
        assertThat(jdbc.queryForObject("select status from ord_sub_order where sub_order_no=?",
                String.class, old)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("★★★ 活码（待履约）与刚结束的单（5 天）都不让：换下一个号")
    void liveAndRecentCodesAreNeverTaken() {
        String live = code();
        String recent = code();
        String fresh = code();
        String liveSub = sub("WAIT_FULFILL", live, 400);
        String recentSub = sub("CANCELLED", recent, 5);

        assertThat(OrderServiceImpl.originalOwnerUnusedVerifyCode(mapper, codes(live, recent, fresh)))
                .isEqualTo(fresh);
        assertThat(jdbc.queryForObject("select verify_code from ord_sub_order where sub_order_no=?",
                String.class, liveSub)).isEqualTo(live);
        assertThat(jdbc.queryForObject("select verify_code from ord_sub_order where sub_order_no=?",
                String.class, recentSub)).isEqualTo(recent);
    }

    @Test
    @DisplayName("★ 连续 8 次都撞活码：照旧抛，不静默复用")
    void allLiveStillThrows() {
        String live = code();
        sub("FULFILLING", live, 400);
        assertThatThrownBy(() -> OrderServiceImpl.originalOwnerUnusedVerifyCode(mapper,
                () -> live)).isInstanceOf(IllegalStateException.class);
    }

    private static java.util.function.Supplier<String> codes(String... seq) {
        Iterator<String> it = List.of(seq).iterator();
        return it::next;
    }

    private static String code() {
        return "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String sub(String status, String code, int daysAgo) {
        String sub = "SVR" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        jdbc.update("INSERT INTO ord_sub_order(sub_order_no,order_no,user_no,entity_no,store_no,status,fulfillment,"
                + "pay_amount,goods_amount,freight_amount,verify_code,created_at,updated_at)"
                + " VALUES(?,?,'USER','MERCHANT','STORE',?,'STORE_PICKUP',0,0,0,?,"
                + "DATEADD('DAY', ?, NOW()),DATEADD('DAY', ?, NOW()))",
                sub, sub, status, code, -daysAgo, -daysAgo);
        return sub;
    }
}

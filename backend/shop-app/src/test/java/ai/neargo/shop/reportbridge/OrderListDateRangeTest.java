package ai.neargo.shop.reportbridge;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import ai.neargo.shop.trade.service.MerchantOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单列表的时间区间（P4，TDD §2.6）。
 *
 * <p><b>它是三张报表共同的下钻出口</b>：没有它，报表上每个数字都点不进去。
 *
 * <p>这里只测**边界** —— 其余（权限、门店范围）既有测试已经覆盖。
 *
 * <p><b>边界要防的那个错是「忘了 +1 天」</b>：{@code to} 若写成
 * {@code < to 00:00}，整个 {@code to} 当天都会被排除 ——
 * 从报表点「今天」进来会得到一个空列表，而商家刚看到今天有 15 单。
 *
 * <p>⚠️ 起草时我写的理由是另一个：「{@code <= to 23:59:59} 会漏掉那一秒里的单」。
 * 那条**对这张表不成立** —— {@code ord_sub_order.created_at} 是
 * {@code datetime(0)}，存不住小数秒，两种写法对它能存的每一个值都等价。
 * 选 {@code < to+1} 是为了将来加精度时仍然对，不是因为今天能看出差别。
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderListDateRangeTest {

    private static final String ENTITY = "M0001";
    private static final String STORE = "S-RANGE-PROBE";

    @Autowired
    private MerchantOrderService orders;

    @Autowired
    private SubOrderMapper subOrders;

    private final List<Long> seeded = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        DataScopeContext.executeWithoutScope(() -> {
            seeded.forEach(subOrders::deleteById);
            return null;
        });
        seeded.clear();
    }

    @Test
    @DisplayName("★★★ to 含那一整天 —— 当天 23:59:59 的单必须进来")
    void toIncludesTheWholeDay() {
        LocalDate today = LocalDate.now();
        // 今天最后一秒的一单。`< to 00:00`（忘了 +1 天）会把它连同整天一起排除
        seed(today.atTime(23, 59, 59));

        var page = orders.list(ENTITY, List.of(STORE), null, null, today, today, 1, 10);

        assertThat(page.total())
                .as("to 若写成 < to 00:00，整个 to 当天都会被排除 —— "
                        + "从报表点「今天」进来会是空列表，而商家刚看到今天有单")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★★ 区间外的单不进来，且 from/to 可以只给一个")
    void filtersOutsideTheRange() {
        LocalDate today = LocalDate.now();
        seed(today.minusDays(5).atTime(12, 0));
        seed(today.atTime(12, 0));

        // 只给 from：从今天起 → 只剩今天那一单
        assertThat(orders.list(ENTITY, List.of(STORE), null, null, today, null, 1, 10).total())
                .isEqualTo(1);
        // 只给 to：截至 6 天前 → 两单都在区间外
        assertThat(orders.list(ENTITY, List.of(STORE), null, null, null, today.minusDays(6), 1, 10).total())
                .isZero();
        // 都不给 = 不限 —— 对照量，证明上面两条不是因为探针没进去才为 0
        assertThat(orders.list(ENTITY, List.of(STORE), null, null, null, null, 1, 10).total())
                .as("两单都没查到 —— 这个测试正在空转")
                .isEqualTo(2);
    }

    private void seed(java.time.LocalDateTime at) {
        OrdSubOrder o = new OrdSubOrder();
        long n = System.nanoTime();
        o.setSubOrderNo("SO-RANGE-PROBE-" + n);
        o.setOrderNo("O-RANGE-PROBE-" + n);
        o.setUserNo("U-RANGE-PROBE");
        o.setEntityNo(ENTITY);
        o.setStoreNo(STORE);
        o.setStatus(OrdSubOrder.COMPLETED);
        o.setPayAmount(1_000L);
        o.setDeleted(0);
        o.setCreatedAt(at);
        DataScopeContext.executeWithoutScope(() -> subOrders.insert(o));
        seeded.add(o.getId());
    }
}

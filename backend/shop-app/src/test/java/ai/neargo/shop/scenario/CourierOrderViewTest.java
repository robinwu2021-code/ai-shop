package ai.neargo.shop.scenario;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.trade.dto.CourierOrderVO;
import ai.neargo.shop.trade.dto.OrderVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配送员的订单视图裁剪（需求 §4.4 的 🟡 那一行）。
 *
 * <p>这条需求此前是全方案唯一没落地的一项：`COURIER` 与店长拿到同一个完整 `OrderVO`，
 * 含四档金额与核销码。**它不构成越权**（核销端点仍要 `biz:verify`），
 * 所以没有任何症状 —— 正是这类「看起来一切正常」的缺口需要测试守着。
 */
class CourierOrderViewTest {

    private static final OrderVO FULL = new OrderVO(
            "SUB-1", "PAY-1", "TO_DELIVER", "MERCHANT_DELIVERY", "M-1", "文三路店",
            List.of(new OrderVO.ItemVO("G-1", "M-1", "SKU-1", "牛奶", null, "1L",
                            580L, 2, 1160L, "GOODS", false),
                    new OrderVO.ItemVO("G-2", "M-1", "SKU-2", "鸡蛋", null, "10 枚",
                            990L, 1, 990L, "GOODS", false)),
            OrderVO.Amount.of(2150L, 300L, 0L, 2450L, "CNY"),
            "8812", "PK-1", "小区自提点", null, 1_700_000_000_000L, null,
            null, "MERCHANT_OWNED", List.of(), null);

    // ---------------------------------------------------------------- 谁该被裁

    @Test
    @DisplayName("★★ 只有配送员这一个角色时才裁 —— 店员兼配送不裁")
    void narrowsOnlyWhenCourierIsTheSoleSourceOfOrderView() {
        assertThat(BizPerms.onlyCourierOrderView(Set.of(BizPerms.COURIER)))
                .as("纯配送员：该裁").isTrue();

        /*
         * 小店的常态是一人多岗。按「有没有 COURIER 这个角色」判的话，
         * 站收银台顺手把货送了的那个人，他的订单页会被裁掉金额 ——
         * 而那是他收银要用的。并集语义里更宽的那一档说了算。
         */
        assertThat(BizPerms.onlyCourierOrderView(Set.of(BizPerms.COURIER, BizPerms.CLERK)))
                .as("店员兼配送：不该裁").isFalse();
        assertThat(BizPerms.onlyCourierOrderView(Set.of(BizPerms.COURIER, BizPerms.CS)))
                .as("客服兼配送：不该裁").isFalse();
    }

    @Test
    @DisplayName("★ 不含配送员的角色一律不裁；老板与空角色也不裁")
    void doesNotNarrowOthers() {
        assertThat(BizPerms.onlyCourierOrderView(Set.of(BizPerms.MANAGER))).isFalse();
        assertThat(BizPerms.onlyCourierOrderView(Set.of(BizPerms.OWNER))).isFalse();
        assertThat(BizPerms.onlyCourierOrderView(Set.of())).isFalse();
        assertThat(BizPerms.onlyCourierOrderView(null)).isFalse();
        // 理货员没有 order:view，压根到不了这一步；到了也不该被当成配送员
        assertThat(BizPerms.onlyCourierOrderView(Set.of(BizPerms.PICKER))).isFalse();
    }

    @Test
    @DisplayName("★★ 角色跟着门店走 —— 在 A 店是纯配送员就裁，在 B 店兼了店员就不裁")
    void followsCurrentStore() {
        BizContext ctx = new BizContext("M-1", Set.of(), Set.of(),
                Set.of("ST-A", "ST-B"), "ST-A", false,
                Map.of("ST-A", Set.of(BizPerms.COURIER),
                        "ST-B", Set.of(BizPerms.COURIER, BizPerms.CLERK)));

        assertThat(ctx.courierOnlyOrderView()).as("A 店：纯配送员").isTrue();
        assertThat(ctx.withStore("ST-B").courierOnlyOrderView()).as("B 店：兼店员").isFalse();
    }

    // ---------------------------------------------------------------- 裁掉了什么

    @Test
    @DisplayName("★★★ 裁剪档不含金额、不含核销码 —— 换类型而不是藏字段")
    void narrowedViewCarriesNoMoneyAndNoVerifyCode() {
        CourierOrderVO cut = CourierOrderVO.of(FULL);

        /*
         * 断言的是**类型上没有这些字段**，不是「值为 null」。
         * 后者只能防住今天，防不住明天某个人给它补上值。
         */
        List<String> fields = java.util.Arrays.stream(CourierOrderVO.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertThat(fields)
                .as("配送员视图里出现了不该有的字段 —— 加字段前先问「他送货需要它吗」")
                .doesNotContain("amount", "verifyCode", "items", "payOrderNo", "trafficSource");

        assertThat(cut.orderNo())
                .as("字段名要与 OrderVO 订单视角一致，否则端上要按返回类型分支取号")
                .isEqualTo("SUB-1");
        assertThat(cut.status()).isEqualTo("TO_DELIVER");
        assertThat(cut.itemQty()).as("件数要合计：他要知道搬几件").isEqualTo(3);
    }

    @Test
    @DisplayName("★ 完整档一个字段都没少 —— 裁剪不能改到别人的视图")
    void fullViewIsUntouched() {
        assertThat(FULL.amount().paidMinor()).isEqualTo(2450L);
        assertThat(FULL.verifyCode()).isEqualTo("8812");
    }

    @Test
    @DisplayName("★ 空明细的单件数为 0，不抛异常")
    void toleratesEmptyItems() {
        OrderVO noItems = new OrderVO("SUB-2", null, "PAID", "MERCHANT_DELIVERY", "M-1", null,
                null, null, null, null, null, null, 0L, null, null, null, List.of(), null);
        assertThat(CourierOrderVO.of(noItems).itemQty()).isZero();
    }
}

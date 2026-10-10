package ai.neargo.shop.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「这笔单买了什么」这一句话。
 *
 * <p><b>它是用户在微信里唯一能认出这笔单的东西</b> ——
 * 微信「我-小店与卡包-小程序购物订单」里显示的商品信息就是它
 * （来自支付下单的 {@code description}），物流卡片上的也是它
 * （来自发货信息录入的 {@code item_desc}）。
 * 此前支付那一侧传的是 {@code "订单 O202609200001"}，于是列表里每一单
 * 长得都一样 —— 而「找回订单」正是这个能力存在的理由。
 */
class GoodsDescTest {

    @Test
    @DisplayName("★★★ 单件：就是商品名本身，不带任何前缀 —— 用户认的是商品不是单号")
    void singleItemIsJustTheTitle() {
        assertThat(GoodsDesc.of(List.of("阳光玫瑰青提"), GoodsDesc.PAY_MAX))
                .isEqualTo("阳光玫瑰青提");
    }

    @Test
    @DisplayName("多件：第一件 + 等N件")
    void multipleItemsGetACount() {
        assertThat(GoodsDesc.of(List.of("阳光玫瑰青提", "香梨", "柠檬"), GoodsDesc.PAY_MAX))
                .isEqualTo("阳光玫瑰青提等3件");
    }

    @Test
    @DisplayName("空标题不算一件 —— 计数按「能显示出来的」算，否则「等3件」里有两件是空的")
    void blankTitlesDoNotCount() {
        assertThat(GoodsDesc.of(Arrays.asList("柠檬", "", null, "  "), GoodsDesc.PAY_MAX))
                .isEqualTo("柠檬");
    }

    @Test
    @DisplayName("第一件是空的就往后找 —— 不能因为第一行没名字就整单没描述")
    void skipsLeadingBlanks() {
        assertThat(GoodsDesc.of(Arrays.asList("", "香梨"), GoodsDesc.PAY_MAX))
                .isEqualTo("香梨");
    }

    @Test
    @DisplayName("★★★ 超长：先给「等N件」留位置再截标题，总长不越界")
    void tailAlwaysFits() {
        String longTitle = "长".repeat(200);
        String got = GoodsDesc.of(List.of(longTitle, "香梨"), GoodsDesc.SHIPPING_MAX);
        assertThat(got)
                .as("越界的话微信是拒还是静默截不确定，而被拒的代价是这笔钱结不出来")
                .hasSizeLessThanOrEqualTo(GoodsDesc.SHIPPING_MAX)
                .endsWith("等2件");
    }

    @Test
    @DisplayName("★★★ 一个能用的标题都没有时返回空串，绝不兜「商品」这类占位词")
    void noUsableTitleYieldsEmpty() {
        assertThat(GoodsDesc.of(List.of(), GoodsDesc.PAY_MAX)).isEmpty();
        assertThat(GoodsDesc.of(null, GoodsDesc.PAY_MAX)).isEmpty();
        assertThat(GoodsDesc.of(Arrays.asList("", null), GoodsDesc.PAY_MAX))
                .as("兜了之后微信收下，而用户在订单列表里看到的每一单都叫「商品」")
                .isEmpty();
    }

    @Test
    @DisplayName("两处上限不同，且发货那一侧更紧 —— 用错常量会在快递单上被微信拒")
    void theTwoLimitsDiffer() {
        assertThat(GoodsDesc.SHIPPING_MAX).isLessThan(GoodsDesc.PAY_MAX);
    }
}

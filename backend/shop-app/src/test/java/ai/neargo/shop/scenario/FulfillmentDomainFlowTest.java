package ai.neargo.shop.scenario;

import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.product.service.MerchantGoodsService;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 履约方式取值域（落地清单 F-1）。
 *
 * <p>修的是两个缺口：
 * <ol>
 *   <li>商品侧 {@code prd_goods.fulfillments} 是<b>无取值域的自由 JSON</b>，
 *       且建商品时写死 {@code ["STORE_PICKUP"]}、商家改不了 ——
 *       「这件商品支持怎么送」在商品侧从未被真正表达过；</li>
 *   <li>{@code SkuSnapshot#fulfillments} 的注释写着「决定拆单后每个子单能选什么」，
 *       <b>而那个校验从来没写过</b> —— 只支持自提的商品可以被下成快递单，
 *       一路走到商家的待发货列表。</li>
 * </ol>
 *
 * <p>取值域里<b>没有「平台仓发货」</b>：平台无仓、不碰货（方案 §7.4）。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("履约方式：商品侧终于能表达「这件货怎么送」")
class FulfillmentDomainFlowTest {

    @Autowired
    private MerchantGoodsService goodsService;

    @Test
    @DisplayName("★ 取值域是单一来源：订单侧常量就是 base 里那四个")
    void singleSourceOfTruth() {
        assertThat(OrdSubOrder.STORE_PICKUP).isEqualTo(Fulfillments.STORE_PICKUP);
        assertThat(OrdSubOrder.EXPRESS).isEqualTo(Fulfillments.EXPRESS);
        assertThat(Fulfillments.ALL)
                .as("没有「平台仓发货」—— 平台无仓、不碰货，只对接第三方物流")
                .containsExactlyInAnyOrder(Fulfillments.STORE_PICKUP, Fulfillments.NEIGHBOR_PICKUP,
                        Fulfillments.MERCHANT_DELIVERY, Fulfillments.EXPRESS);
    }

    @Test
    @DisplayName("★ 自提与配送要分得开 —— 两者的校验、履约、结算分支都不同")
    void pickupIsDistinguishable() {
        assertThat(Fulfillments.isPickup(Fulfillments.STORE_PICKUP)).isTrue();
        assertThat(Fulfillments.isPickup(Fulfillments.NEIGHBOR_PICKUP)).isTrue();
        assertThat(Fulfillments.isPickup(Fulfillments.MERCHANT_DELIVERY)).isFalse();
        assertThat(Fulfillments.isPickup(Fulfillments.EXPRESS)).isFalse();
    }

    @Test
    @DisplayName("★ 新建商品默认四种全支持 —— 由商家收窄，而不是一建出来就只能自提")
    void newGoodsDefaultsToAll() {
        var vo = goodsService.save("MFUL1", saveCmd(null, null));

        assertThat(vo.fulfillments())
                .as("默认放宽、由商家收窄，是唯一不会凭空拦单的方向")
                .containsExactlyInAnyOrderElementsOf(Fulfillments.ALL);
    }

    @Test
    @DisplayName("★ 商家可以收窄到只支持自提")
    void merchantCanNarrow() {
        var created = goodsService.save("MFUL2", saveCmd(null, null));

        var vo = goodsService.save("MFUL2",
                saveCmd(created.goodsNo(), List.of(Fulfillments.STORE_PICKUP)));

        assertThat(vo.fulfillments()).containsExactly(Fulfillments.STORE_PICKUP);
    }

    @Test
    @DisplayName("★★ 空数组要拒 —— 一种都不支持的商品谁也买不了，且看起来与正常商品无异")
    void emptyListRejected() {
        var created = goodsService.save("MFUL3", saveCmd(null, null));

        assertThatThrownBy(() -> goodsService.save("MFUL3", saveCmd(created.goodsNo(), List.of())))
                .as("不传（null）才是「不改」；传空数组是另一件事")
                .isInstanceOf(ai.neargo.shop.common.BizException.class);
    }

    @Test
    @DisplayName("★ 取值域外的值直接拒 —— 自由 JSON 正是这次要消灭的东西")
    void unknownValueRejected() {
        var created = goodsService.save("MFUL4", saveCmd(null, null));

        assertThatThrownBy(() ->
                goodsService.save("MFUL4", saveCmd(created.goodsNo(), List.of("DRONE"))))
                .isInstanceOf(ai.neargo.shop.common.BizException.class);
    }

    @Test
    @DisplayName("★ 不传 fulfillments = 不改，不会把已有取值清掉")
    void nullMeansUnchanged() {
        var created = goodsService.save("MFUL5", saveCmd(null, List.of(Fulfillments.EXPRESS)));
        assertThat(created.fulfillments()).containsExactly(Fulfillments.EXPRESS);

        var again = goodsService.save("MFUL5", saveCmd(created.goodsNo(), null));

        assertThatCode(() -> assertThat(again.fulfillments()).containsExactly(Fulfillments.EXPRESS))
                .doesNotThrowAnyException();
    }

    private MerchantGoodsService.SaveCommand saveCmd(String goodsNo, List<String> fulfillments) {
        return new MerchantGoodsService.SaveCommand(
                goodsNo, "履约测试商品", null, null, null, "GOODS", null, null, List.of(),
                List.of(), List.of(new MerchantGoodsService.Sku(null, List.of(), 1000L, null, 10)),
                fulfillments);
    }
}

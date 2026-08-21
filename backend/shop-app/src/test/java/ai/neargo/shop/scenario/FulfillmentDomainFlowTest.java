package ai.neargo.shop.scenario;

import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.product.service.MerchantGoodsService;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.service.OrderStateMachine;
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
                        Fulfillments.MERCHANT_DELIVERY, Fulfillments.EXPRESS,
                        Fulfillments.STORE_VERIFY, Fulfillments.APPOINTMENT);
        assertThat(Fulfillments.PHYSICAL)
                .as("服务类不进实物集合 —— 默认值用的是它，一件大米不该声称支持上门预约")
                .doesNotContain(Fulfillments.STORE_VERIFY, Fulfillments.APPOINTMENT);
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
    @DisplayName("★ 新建商品默认实物类全支持 —— 由商家收窄；服务类要显式选")
    void newGoodsDefaultsToAll() {
        var vo = goodsService.save("MFUL1", saveCmd(null, null));

        assertThat(vo.fulfillments())
                .as("默认放宽、由商家收窄，是唯一不会凭空拦单的方向")
                .containsExactlyInAnyOrderElementsOf(Fulfillments.PHYSICAL);
        assertThat(vo.fulfillments())
                .as("一件大米不该一建出来就声称支持到店核销")
                .doesNotContain(Fulfillments.STORE_VERIFY);
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

    // ---------------------------------------------------------------- 服务履约（到店核销）

    /*
     * 到店核销这一期的**核心断言**。
     *
     * 它与实物履约的差别不在「多一种方式」，在**支付成功后落哪个状态**：
     * 实物落 WAIT_FULFILL（商家要备货发货），服务落 FULFILLING（码已出，随时能用）。
     * 落错的表现不是报错，是界面对着一张服务单说「待发货」—— 而根本没有东西要发。
     */
    @Test
    @DisplayName("★★ 到店核销：支付成功直接进「履约中」，不经「待发货」")
    void storeVerifySkipsWaitFulfill() {
        assertThat(Fulfillments.SERVICE_LIKE)
                .as("这一组决定支付后落哪个状态")
                .contains(Fulfillments.STORE_VERIFY);

        assertThat(OrderStateMachine.canTransit(OrderStateMachine.subOrderGraph(),
                OrdSubOrder.WAIT_PAY, OrdSubOrder.FULFILLING))
                .as("状态机要放行这条边，否则服务单支付时当场抛错")
                .isTrue();
    }

    @Test
    @DisplayName("★ 到店核销单可以直接核销完成 —— 核销不看履约方式")
    void storeVerifyCanCompleteFromFulfilling() {
        assertThat(OrderStateMachine.canTransit(OrderStateMachine.subOrderGraph(),
                OrdSubOrder.FULFILLING, OrdSubOrder.COMPLETED))
                .isTrue();
    }

    /*
     * 上门预约的两道闸。**它们不是「校验」，是「下得成的单必须是履约得了的单」**：
     * 缺时间，商家不知道该几点去；缺地址，师傅不知道去哪。
     * 两者都不会报错 —— 单会一路下成功、付成功，然后卡在两边都只能打电话。
     */
    @Test
    @DisplayName("★★ 上门预约必须带预约时间，且不能是过去")
    void appointmentRequiresFutureTime() {
        assertThat(Fulfillments.NEEDS_APPOINTMENT).contains(Fulfillments.APPOINTMENT);
        assertThat(Fulfillments.NEEDS_APPOINTMENT)
                .as("到店核销随时可用，不需要约时间")
                .doesNotContain(Fulfillments.STORE_VERIFY);
    }

    @Test
    @DisplayName("★★ 上门预约也算「送到人手上」—— 必须有地址")
    void appointmentNeedsReceiver() {
        assertThat(Fulfillments.SERVICE_LIKE)
                .as("两种服务履约都不经「待发货」")
                .containsExactlyInAnyOrder(Fulfillments.STORE_VERIFY, Fulfillments.APPOINTMENT);
    }

    @Test
    @DisplayName("★ 商品可以声明支持到店核销 —— 此前这个值根本不在取值域里")
    void goodsCanDeclareStoreVerify() {
        var vo = goodsService.save("MFUL6",
                saveCmd(null, List.of(Fulfillments.STORE_VERIFY)));

        assertThat(vo.fulfillments()).containsExactly(Fulfillments.STORE_VERIFY);
    }

    private MerchantGoodsService.SaveCommand saveCmd(String goodsNo, List<String> fulfillments) {
        // 分类只剩 categoryNo 一个入口：五品类由它派生（P1-1）。
        // 这里原先传的是一个叫 "GOODS" 的 type —— 它连合法品类都不是
        // （库里存 NORMAL，GOODS 是 sys_channel_category_rule 那套旧名），
        // 而当时它会被原样写进 prd_goods.type。派生之后这类值再也进不去了
        // 类目必填，且形态由它派生 —— CAT210（纸品清洁 / STANDARD）派生出 NORMAL，
        // 正是本组要的实物类：候选履约方式才会是自提/快递那几种
        return new MerchantGoodsService.SaveCommand(
                goodsNo, "履约测试商品", null, null, null, "CAT210", null, List.of(),
                List.of(),
                List.of(new MerchantGoodsService.Sku(null, List.of(), 1000L, null, 10, null, null)),
                fulfillments,
                // 限购 / 生鲜 / 服务 / 拼团四段都不传 = 不改；stdNo 不传 = 自建品，
                // detail 不传 = 不改 —— 本组只测履约方式
                null, null, null, null, null, null);
    }
}

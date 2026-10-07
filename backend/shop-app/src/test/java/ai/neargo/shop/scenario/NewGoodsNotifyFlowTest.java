package ai.neargo.shop.scenario;

import ai.neargo.shop.notify.port.StubWxSubscribeGateway;
import ai.neargo.shop.spi.product.ProductEvents;
import ai.neargo.shop.user.entity.UsrStoreFavorite;
import ai.neargo.shop.user.mapper.UserMappers.StoreFavoriteMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 新品开售提醒全链路（TDD-C 端裂变与商家招募 §10）。
 *
 * <p>走真实链路：微信登录（带 openid）→ 收藏店铺 → 上报订阅授权（额度 +1）→
 * 发 {@code NEW_GOODS_ON_SALE} 事件 → Outbox 投递 → 扇出给收藏者 → 订阅消息进桩。
 *
 * <p><b>收藏行与订阅行都是共享种子</b>：不还原的话，后面的用例会读到我造的收藏关系，
 * 而报错会指向一个毫不相干的地方。
 */
@SpringBootTest
@ActiveProfiles("test")
class NewGoodsNotifyFlowTest {

    /** 桩世界的模板号（与 {@code StubWxSubscribeGateway#templateId} 一致）。 */
    private static final String TPL_NEW_GOODS = "STUB_TPL_NEW_GOODS";
    /** 独占商家号：用共享的 M0001 会把别人的收藏者卷进来 */
    private static final String MERCHANT = "M0001";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ai.neargo.shop.event.OutboxDispatcher dispatcher;
    @Autowired
    private ai.neargo.shop.event.OutboxEventBus eventBus;
    @Autowired
    private StubWxSubscribeGateway wxStub;
    @Autowired
    private StoreFavoriteMapper favoriteMapper;
    @Autowired
    private ai.neargo.shop.spi.user.StoreFavoritePort storeFavoritePort;
    @Autowired
    private ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper goodsMapper;
    @Autowired
    private ai.neargo.shop.product.service.MerchantGoodsService goodsService;

    private String createdFavoriteUserNo;

    @BeforeEach
    void drainOutbox() {
        // 前面用例堆下的事件会把本类的挤出一批 200 条的窗口（同 WxNotifyFlowTest）
        for (int i = 0; i < 50 && dispatcher.pendingCount() > 0; i++) {
            dispatcher.dispatchPending();
        }
        wxStub.clear();
    }

    @AfterEach
    void restoreSeed() {
        if (createdFavoriteUserNo != null) {
            favoriteMapper.delete(Wrappers.<UsrStoreFavorite>lambdaQuery()
                    .eq(UsrStoreFavorite::getUserNo, createdFavoriteUserNo));
            createdFavoriteUserNo = null;
        }
    }

    @Test
    @DisplayName("★★★ 收藏 + 授权 → 上新 → 收藏者收到订阅消息，且带着引导续订那句话")
    void followerGetsNewGoodsMessage() throws Exception {
        String openId = "wx-open-newgoods-1";
        String token = ai.neargo.shop.support.TestLogin.consumerByWechat(mvc(), json, openId);
        createdFavoriteUserNo = profileUserNo(token);

        favorite(token);
        subscribe(token, TPL_NEW_GOODS, true);

        publishNewGoods("G-NG-1", "阳光玫瑰青提", "当季头茬");

        List<StubWxSubscribeGateway.Sent> sent = sentTo(openId);
        assertThat(sent).as("收藏者一条都没收到 —— 扇出断了").hasSize(1);
        assertThat(sent.getFirst().scene()).isEqualTo("NEW_GOODS");
        assertThat(sent.getFirst().summary()).contains("阳光玫瑰青提");
        assertThat(sent.getFirst().summary())
                .as("没有引导续订那句话 —— 一次授权只够一条，不说用户会以为还订阅着")
                .contains("再点一次收藏");
    }

    @Test
    @DisplayName("★★★ 额度只有一份：第二次上新收不到 —— 这是设计，不是缺陷")
    void quotaIsOneShot() throws Exception {
        String openId = "wx-open-newgoods-2";
        String token = ai.neargo.shop.support.TestLogin.consumerByWechat(mvc(), json, openId);
        createdFavoriteUserNo = profileUserNo(token);

        favorite(token);
        subscribe(token, TPL_NEW_GOODS, true);

        publishNewGoods("G-NG-2", "头茬香椿", "早春限定");
        publishNewGoods("G-NG-3", "第二件新品", "同一天又上一件");

        assertThat(sentTo(openId))
                .as("一次授权发出去两条 —— 额度没扣或扣错了模板号").hasSize(1);
    }

    @Test
    @DisplayName("★★★ 没收藏这家店的人收不到 —— 扇出不能扩散到全体用户")
    void nonFollowerGetsNothing() throws Exception {
        String openId = "wx-open-newgoods-3";
        String token = ai.neargo.shop.support.TestLogin.consumerByWechat(mvc(), json, openId);
        profileUserNo(token);

        // 只授权、不收藏
        subscribe(token, TPL_NEW_GOODS, true);

        publishNewGoods("G-NG-4", "不该收到的新品", "");

        assertThat(sentTo(openId)).isEmpty();
    }

    @Test
    @DisplayName("★★ 收藏了但没授权 → 静默跳过，不报错")
    void followerWithoutQuotaIsSkipped() throws Exception {
        String openId = "wx-open-newgoods-4";
        String token = ai.neargo.shop.support.TestLogin.consumerByWechat(mvc(), json, openId);
        createdFavoriteUserNo = profileUserNo(token);

        favorite(token);   // 收藏了，但没点「允许」

        publishNewGoods("G-NG-5", "没授权就收不到", "");

        assertThat(sentTo(openId)).isEmpty();
    }

    /**
     * <b>这条守的是「跨所有用户查」这件事本身。</b>
     *
     * <p>原本写它是为了钉住 {@code executeWithoutScope}，结果消融两次都没红 ——
     * 查 {@code DataScopeRegistration} 才知道登记的 114 张表里没有任何 {@code usr_} 表，
     * 数据域根本管不到收藏。那个 bypass 是多余的，已经删掉。
     *
     * <p>留下这条测试是因为它换个说法仍然成立，而且守的是真会发生的事：
     * user 域里其余查询都按 {@code SecurityUtils.currentUserNo()} 过滤，
     * 只有这一条是跨用户的。谁顺手给它加一个 userNo 条件，
     * 上新通知就会只发给「当前登录的那个人」—— 而 Outbox 投递线程里没有登录的人，
     * 于是一条都不发，零报错。这里用**另一个人的登录态**去查，那种写法会当场变红。
     */
    @Test
    @DisplayName("★★★ 换谁来调都查得到这家店的收藏者 —— 它是跨用户查询，不是「我的收藏」")
    void followersVisibleRegardlessOfCaller() throws Exception {
        String openId = "wx-open-newgoods-5";
        String token = ai.neargo.shop.support.TestLogin.consumerByWechat(mvc(), json, openId);
        createdFavoriteUserNo = profileUserNo(token);
        favorite(token);

        // 另一个人的登录态：如果实现按「当前用户」过滤，这里就查不到上面那个人
        String otherOpenId = "wx-open-newgoods-5b";
        String otherToken = ai.neargo.shop.support.TestLogin.consumerByWechat(mvc(), json, otherOpenId);
        String otherUserNo = profileUserNo(otherToken);
        var self = ai.neargo.common.data.scope.DataScopeSpec.of(
                ai.neargo.shop.auth.ScopeDim.SELF, java.util.Set.of(otherUserNo));
        try {
            ai.neargo.common.data.scope.DataScopeContext.set(self);
            assertThat(storeFavoritePort.followerUserNos(MERCHANT))
                    .as("按调用者过滤了 —— 上新通知会只发给「当前登录的人」，"
                            + "而投递线程里没有登录的人，于是一条都不发")
                    .contains(createdFavoriteUserNo);
        } finally {
            ai.neargo.common.data.scope.DataScopeContext.clear();
        }
    }

    /**
     * <b>AC3：同一件商品只通知一次。</b>
     *
     * <p>这是防刷屏的**唯一**一道闸。没有它，商家把一件货来回上下架就能给收藏者刷屏，
     * 而每一条都是合法的「新品开售」—— 不报错、日志上看也正常。
     *
     * <p>判据取 {@code prd_goods.new_notified_at}：直接调 service 的上下架，
     * 走的是与 `/biz/goods/{no}/toggle` 同一条路（{@code onSaleSideEffects} 是所有上架路径的共同出口）。
     */
    @Test
    @DisplayName("★★★ 下架再上架不再通知 —— 否则来回切几次就能刷屏")
    void reListingDoesNotNotifyAgain() {
        var g = goodsMapper.selectOne(Wrappers.<ai.neargo.shop.product.entity.PrdGoods>lambdaQuery()
                .eq(ai.neargo.shop.product.entity.PrdGoods::getEntityNo, MERCHANT)
                .isNull(ai.neargo.shop.product.entity.PrdGoods::getNewNotifiedAt)
                .eq(ai.neargo.shop.product.entity.PrdGoods::getAuditStatus, "APPROVED")
                .last("limit 1"));
        org.junit.jupiter.api.Assumptions.assumeTrue(g != null,
                "种子里没有「审核通过且没通知过」的商品，这条测不了");
        String goodsNo = g.getGoodsNo();
        try {
            goodsService.toggle(MERCHANT, goodsNo, true);
            Long first = reload(goodsNo).getNewNotifiedAt();
            assertThat(first).as("首次上架没有写 new_notified_at —— 幂等闸门根本没落地").isNotNull();

            goodsService.toggle(MERCHANT, goodsNo, false);
            goodsService.toggle(MERCHANT, goodsNo, true);
            assertThat(reload(goodsNo).getNewNotifiedAt())
                    .as("重新上架把时间戳改了 —— 那等于每次上架都会再通知一遍")
                    .isEqualTo(first);
        } finally {
            // 共享种子：时间戳与上架态都要还原，否则后面的用例读到的是我改过的商品
            var back = reload(goodsNo);
            back.setNewNotifiedAt(null);
            back.setOnSale(g.getOnSale());
            goodsMapper.updateById(back);
        }
    }

    private ai.neargo.shop.product.entity.PrdGoods reload(String goodsNo) {
        return goodsMapper.selectOne(Wrappers.<ai.neargo.shop.product.entity.PrdGoods>lambdaQuery()
                .eq(ai.neargo.shop.product.entity.PrdGoods::getGoodsNo, goodsNo).last("limit 1"));
    }

    // ------------------------------------------------------------------ helpers

    private void publishNewGoods(String goodsNo, String title, String desc) {
        eventBus.publish(new ProductEvents.NewGoodsOnSale(
                goodsNo, MERCHANT, title, desc, System.currentTimeMillis()));
        for (int i = 0; i < 10 && dispatcher.pendingCount() > 0; i++) {
            dispatcher.dispatchPending();
        }
    }

    private List<StubWxSubscribeGateway.Sent> sentTo(String openId) {
        return wxStub.sent().stream().filter(s -> openId.equals(s.openId())).toList();
    }

    private void favorite(String token) throws Exception {
        mvc().perform(post("/mp/favorite/store/" + MERCHANT)
                .header("Authorization", "Bearer " + token));
    }

    private void subscribe(String token, String templateId, boolean accepted) throws Exception {
        mvc().perform(post("/mp/message/subscribe").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"templateIds\":[\"" + templateId + "\"],\"accepted\":" + accepted + "}"));
    }

    private String profileUserNo(String token) throws Exception {
        String body = mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("cUserNo").asString();
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }
}

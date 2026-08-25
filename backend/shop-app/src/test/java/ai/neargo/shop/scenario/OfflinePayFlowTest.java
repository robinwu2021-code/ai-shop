package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.common.PayModes;
import ai.neargo.shop.merchant.entity.MchAccount;
import ai.neargo.shop.merchant.entity.MchQualification;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.merchant.service.StoreFulfillmentService;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.mapper.ProductMappers;
import ai.neargo.shop.spi.user.QualificationPort;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.mapper.TradeMappers;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 线下支付：下单落新状态、商家确认收款、以及三条**不许发生**的事。
 *
 * <p>本类守的是 {@code create} 这条全站最要害的路径上新加的那几道闸。
 * 每条用例都对应一个具体的资损或越权场景，不是「跑通就行」。
 */
@SpringBootTest
@ActiveProfiles("test")
class OfflinePayFlowTest {

    /** 种子商家 M0001 的默认门店 —— 与 schema-test 里的种子一致 */
    private static final String SEED_ENTITY = "M0001";
    private static final String STAFF_PHONE = "13100888001";

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ProductMappers.GoodsMapper goodsMapper;
    @Autowired
    private MerchantMappers.MchStoreMapper storeMapper;
    @Autowired
    private MerchantMappers.QualificationMapper qualMapper;
    @Autowired
    private TradeMappers.OrderMapper orderMapper;
    @Autowired
    private TradeMappers.SubOrderMapper subOrderMapper;
    @Autowired
    private MerchantMappers.MchAccountMapper staffMapper;
    @Autowired
    private StoreFulfillmentService fulfillmentService;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    /** 让 M0001 具备线下收款资格：有证 + 门店开关打开 + 商品支持。 */
    @BeforeEach
    void openOfflinePay() {
        DataScopeContext.executeWithoutScope(() -> {
            if (qualMapper.selectCount(Wrappers.<MchQualification>lambdaQuery()
                    .eq(MchQualification::getEntityNo, SEED_ENTITY)) == 0) {
                MchQualification q = new MchQualification();
                q.setQualNo("QUAL_OFFLINE_T1");
                q.setEntityNo(SEED_ENTITY);
                q.setQualType(QualificationPort.BUSINESS_LICENSE);
                q.setQualName("营业执照");
                q.setExpireAt(System.currentTimeMillis() + 10L * 365 * 24 * 3600 * 1000);
                q.setStatus(MchQualification.VALID);
                qualMapper.insert(q);
            }
            for (MchStore st : storeMapper.selectList(Wrappers.<MchStore>lambdaQuery()
                    .eq(MchStore::getEntityNo, SEED_ENTITY))) {
                st.setOfflinePayEnabled(1);
                // 自取的取货地址就是门店地址（服务端刻意不另存一份）。种子店没地址，
                // 下面开自取通道会被 STORE_ADDRESS_REQUIRED 拦住
                if (st.getAddress() == null || st.getAddress().isBlank()) {
                    st.setAddress("测试路 1 号");
                }
                storeMapper.updateById(st);
            }
            for (PrdGoods g : goodsMapper.selectList(Wrappers.<PrdGoods>lambdaQuery()
                    .eq(PrdGoods::getEntityNo, SEED_ENTITY))) {
                g.setPayModes("[\"ONLINE\",\"OFFLINE\"]");
                /*
                 * 履约方式全开。**这不是为了方便，是为了让被测的那道闸真的被走到** ——
                 * 种子商品只支持自提，于是「快递 + 线下付」会先被 FULFILLMENT_NOT_SUPPORTED
                 * 拦掉，支付方式那道闸根本没执行，用例绿了却什么都没验。
                 */
                g.setFulfillments("[\"STORE_PICKUP\",\"NEIGHBOR_PICKUP\",\"MERCHANT_DELIVERY\",\"EXPRESS\"]");
                goodsMapper.updateById(g);
            }
            // B 端员工：种子里没有 M0001 的员工，照 M9aOpsFlowTest 的形态直接造一个
            if (staffMapper.selectCount(Wrappers.<MchAccount>lambdaQuery()
                    .eq(MchAccount::getLoginPhone, STAFF_PHONE)) == 0) {
                MchAccount staff = new MchAccount();
                staff.setMchAccountNo("SF-OFFLINE-1");
                staff.setEntityNo(SEED_ENTITY);
                staff.setLoginPhone(STAFF_PHONE);
                staff.setIsOwner(true);
                staff.setIsPrimary(true);
                staff.setStatus(MchAccount.ACTIVE);
                staffMapper.insert(staff);
            }
            return null;
        });
        /*
         * ⚠️ **门店的履约通道也要自己开。** 商品说支持只是必要条件 ——
         * 下单还要问「这家店这一路开没开」，规则是
         * 「渠道行为空 = 该店还没迁到 channel 模型，按旧口径放行」。
         *
         * 种子店一行都没有，所以本类单独跑时怎么都通。而只要**任何**别的用例
         * 给这家店存过一次渠道（DeliveryRadiusFlowTest 开自送就会建出第一行），
         * 集合就不再是空的 —— 于是本类那三条「断言 80011」的用例会拿到 70013：
         * **被测的支付方式那道闸根本没执行**，用例红得莫名其妙，
         * 而在别的顺序下它们又是绿的，绿得同样莫名其妙。
         *
         * 开成全集是刻意的：空集本来就等价于全放行，这么写只是把它显式化，
         * 不会收窄任何别的用例的前提。
         */
        fulfillmentService.save(SEED_ENTITY, null, List.of(
                new StoreFulfillmentService.ChannelCmd(Fulfillments.STORE_PICKUP, true, null, null, null, null),
                new StoreFulfillmentService.ChannelCmd(Fulfillments.NEIGHBOR_PICKUP, true, null, null, null, null),
                new StoreFulfillmentService.ChannelCmd(Fulfillments.MERCHANT_DELIVERY, true, null, null, null, null),
                new StoreFulfillmentService.ChannelCmd(Fulfillments.EXPRESS, true, null, null, null, null)));
    }

    @Test
    @DisplayName("★★ 线下下单落 WAIT_OFFLINE_PAY，确认收款后才 PAID")
    void offlineOrderWaitsForMerchantConfirmation() throws Exception {
        String token = login("13400188001");
        addToCart(token, "G0001", "SK0001", 1);
        String orderNo = createOffline(token, "offline-happy");

        OrdOrder before = order(orderNo);
        assertThat(before.getStatus())
                .as("钱还没收到就落 PAID 的话，商家收不到钱时要去退一笔平台从没收过的钱")
                .isEqualTo(OrdOrder.WAIT_OFFLINE_PAY);

        confirm(bizToken(), subOrderNoOf(token, orderNo))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(order(orderNo).getStatus()).isEqualTo(OrdOrder.PAID);
        assertThat(order(orderNo).getOfflineConfirmedBy())
                .as("平台不碰这笔钱，能提供的只有这条留痕 —— 缺了它争议就是各执一词")
                .isNotBlank();
    }

    @Test
    @DisplayName("★★★ 买家调不到「确认收款」—— 让他能点等于让他自己宣布已付款")
    void buyerCannotConfirmOfflinePay() throws Exception {
        String token = login("13400188002");
        addToCart(token, "G0001", "SK0001", 1);
        String orderNo = createOffline(token, "offline-buyer");
        String subOrderNo = subOrderNoOf(token, orderNo);

        /*
         * ⚠️ 这个应用**错误也返回 HTTP 200**，业务码在响应体的 code 里。
         * 断 HTTP 状态码会永远绿 —— 我第一版就是这么写的，它连买家能不能调都没验到。
         */
        String body = mvc().perform(post("/biz/order/" + subOrderNo + "/confirm-offline-pay")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).path("code").asInt())
                .as("买家 token 必须过不了 B 端权限闸：让他能点等于让他自己宣布已付款")
                .isNotZero();
    }

    @Test
    @DisplayName("★★ 快递 + 线下付被拒 —— 货已寄出，没有当面收款的那一刻")
    void expressCannotPayOffline() throws Exception {
        String token = login("13400188003");
        addToCart(token, "G0001", "SK0001", 1);
        assertCode(createRaw(token, "offline-express",
                "{\"fulfillment\":\"EXPRESS\",\"addressId\":\"AD0001\",\"payMode\":\"OFFLINE\"}"),
                80011);
    }

    @Test
    @DisplayName("★★ 自提点自提 + 线下付被拒 —— 自提点不是卖家，代收货款就是资金归集")
    void neighborPickupCannotPayOffline() throws Exception {
        String token = login("13400188004");
        addToCart(token, "G0001", "SK0001", 1);
        assertCode(createRaw(token, "offline-neighbor",
                "{\"fulfillment\":\"NEIGHBOR_PICKUP\",\"pickupNo\":\"PP0001\",\"payMode\":\"OFFLINE\"}"),
                80011);
    }

    @Test
    @DisplayName("★ 门店没开线下收款 → 下单被拒（四层判定接进了 create）")
    void storeSwitchOffRejectsOrder() throws Exception {
        DataScopeContext.executeWithoutScope(() -> {
            for (MchStore st : storeMapper.selectList(Wrappers.<MchStore>lambdaQuery()
                    .eq(MchStore::getEntityNo, SEED_ENTITY))) {
                st.setOfflinePayEnabled(0);
                storeMapper.updateById(st);
            }
            return null;
        });
        String token = login("13400188005");
        addToCart(token, "G0001", "SK0001", 1);
        assertCode(createRaw(token, "offline-store-off",
                "{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\",\"payMode\":\"OFFLINE\"}"),
                80011);
    }

    @Test
    @DisplayName("★★★ 买家列表上线下单不能显示成「待付款」—— 端上会给它画一个点了没用的支付按钮")
    void buyerListShowsWaitOfflinePay() throws Exception {
        String token = login("13400188007");
        addToCart(token, "G0001", "SK0001", 1);
        String orderNo = createOffline(token, "offline-listview");

        String body = mvc().perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.get("/mp/order")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        JsonNode row = null;
        for (JsonNode r : json.readTree(body).get("data").get("records")) {
            if (orderNo.equals(r.path("payOrderNo").asString())) {
                row = r;
            }
        }
        assertThat(row).isNotNull();
        /*
         * ⚠️ 这一条钉的是一个**真实缺陷**，第 4 步埋下、第 8 步接界面时才暴露：
         * 订单视角的 status 取自**子单**，而线下单的子单刻意停在 WAIT_PAY
         * （给子单也加一个新状态的话，商家待办、售后入口、履约台三处都要各判一次）。
         * 于是买家列表把它显示成「待付款」，端上据此画「去支付」——
         * 而这单只能当面把钱给商家。
         *
         * 修法是**下发口径由主单推出**，子单那一列不动：读它的三处关心的是
         * 「货走到哪了」，与钱怎么收无关。
         */
        assertThat(row.path("status").asString())
                .as("子单列不用改，改的是下发口径")
                .isEqualTo("WAIT_OFFLINE_PAY");
    }

    @Test
    @DisplayName("★ 下单端快照进 pay_scene —— 积分发放的端判定读它")
    void paySceneIsSnapshotted() throws Exception {
        String token = login("13400188006");
        addToCart(token, "G0001", "SK0001", 1);
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "offline-scene")
                        .header("X-Client", "MP_WECHAT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String orderNo = json.readTree(body).get("data").get("payOrderNo").asString();
        assertThat(order(orderNo).getPayScene())
                .as("这一列 V1 baseline 就有，缺的一直是这行写入")
                .isEqualTo("MP_WECHAT");
    }

    // ── helpers ──────────────────────────────────────────────

    private void assertCode(String body, int expected) {
        assertThat(json.readTree(body).get("code").asInt()).isEqualTo(expected);
    }

    private String createOffline(String token, String idem) throws Exception {
        String body = createRaw(token, idem,
                "{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\",\"payMode\":\"OFFLINE\"}");
        return json.readTree(body).get("data").get("payOrderNo").asString();
    }

    private String createRaw(String token, String idem, String content) throws Exception {
        return mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idem)
                        .contentType(MediaType.APPLICATION_JSON).content(content))
                .andReturn().getResponse().getContentAsString();
    }

    private OrdOrder order(String orderNo) {
        return DataScopeContext.executeWithoutScope(() ->
                orderMapper.selectOne(Wrappers.<OrdOrder>lambdaQuery()
                        .eq(OrdOrder::getOrderNo, orderNo).last("LIMIT 1")));
    }

    /**
     * 取子单号。
     *
     * <p>⚠️ <b>订单列表是「订单视角」，那里的 {@code orderNo} 就是子单号</b>
     * （VO 的类注释写着：订单视角 orderNo = 子单号，支付视角 orderNo = 主单号）。
     * 没有叫 {@code subOrderNo} 的字段 —— 我一开始按它取，拿到空串，
     * 于是请求路径变成 {@code /biz/order//confirm-offline-pay}，报的是 405 不是 404，
     * 查了两轮才明白不是端点没注册。
     */
    private String subOrderNoOf(String token, String payOrderNo) throws Exception {
        String body = mvc().perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.get("/mp/order")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode r : json.readTree(body).get("data").get("records")) {
            if (payOrderNo.equals(r.path("payOrderNo").asString())) {
                return r.path("orderNo").asString();
            }
        }
        throw new AssertionError("没找到 payOrderNo=" + payOrderNo + " 的子单：" + body);
    }

    private org.springframework.test.web.servlet.ResultActions confirm(String biz, String subOrderNo)
            throws Exception {
        return mvc().perform(post("/biz/order/" + subOrderNo + "/confirm-offline-pay")
                .header("Authorization", "Bearer " + biz));
    }

    private String bizToken() throws Exception {
        return TestLogin.merchantStaff(mvc(), json, otpStore, STAFF_PHONE);
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }

    private void addToCart(String token, String goodsNo, String skuNo, int qty) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo
                                + "\",\"qty\":" + qty + "}"))
                .andExpect(status().isOk());
    }
}

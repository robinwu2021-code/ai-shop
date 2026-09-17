package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.community.entity.CmtCommunity;
import ai.neargo.shop.community.entity.CmtPickupPoint;
import ai.neargo.shop.support.TestLogin;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 属于多个自提点就拆单（TDD-C端位置选择-地址取代自提点 §M4b）。
 *
 * <p>订单本来就按商家拆成子单，而 {@code pickup_no}、{@code pickup_name}、
 * {@code pickup_owner_ref}（佣金归属）全在子单上 ——
 * 这套结构从建起来那天就是为「每张子单各有各的点」准备的。
 * 此前只是把同一个 {@code cmd.pickupNo()} 抄进了每一张子单。
 */
@SpringBootTest
@ActiveProfiles("test")
class PickupSplitByMerchantTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private ai.neargo.shop.community.mapper.CommunityMappers.CommunityMapper communityMapper;
    @Autowired
    private ai.neargo.shop.community.mapper.CommunityMappers.PickupPointMapper pickupMapper;
    @Autowired
    private ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper goodsMapper;
    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper storeMapper;

    private static final int LAT = 30_810_000;
    private static final int LNG = 120_810_000;
    private static int seq = 9900;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    /** 这件货是哪家商家的默认门店 —— 自提点挂门店，不挂主体（V16 起） */
    private String storeOf(String goodsNo) {
        String entityNo = DataScopeContext.executeWithoutScope(() -> goodsMapper.selectOne(
                Wrappers.<ai.neargo.shop.product.entity.PrdGoods>lambdaQuery()
                        .eq(ai.neargo.shop.product.entity.PrdGoods::getGoodsNo, goodsNo)
                        .last("limit 1"))).getEntityNo();
        var store = DataScopeContext.executeWithoutScope(() -> storeMapper.selectOne(
                Wrappers.<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchStore::getEntityNo, entityNo)
                        .last("limit 1")));
        assertThat(store).as("夹具：%s 的商家要有门店，否则许可点恒为空集", goodsNo).isNotNull();
        return store.getStoreNo();
    }

    private String pickupFor(String storeNo, String communityNo, int latOffset) {
        var p = new CmtPickupPoint();
        p.setPickupNo("SPP" + seq++);
        p.setName("拆单测试点" + seq);
        p.setAddress("测试地址");
        p.setCommunityNo(communityNo);
        p.setType("STORE");
        p.setStatus("ACTIVE");
        p.setOwnerRef(storeNo);
        p.setLatE6(LAT + latOffset);
        p.setLngE6(LNG);
        DataScopeContext.executeWithoutScope(() -> pickupMapper.insert(p));
        return p.getPickupNo();
    }

    @Test
    @DisplayName("★★★ 两家商家各自承接一个点 → 不传 pickupNo，两张子单各落各的")
    void eachMerchantGetsItsOwnPickup() throws Exception {
        var c = new CmtCommunity();
        c.setCommunityNo("SPC" + seq++);
        c.setName("拆单测试小区");
        c.setStatus("OPEN");
        c.setRegionCode("330106061");
        c.setKind(CmtCommunity.KIND_ESTATE);
        c.setFenceRadius(1000);
        c.setLatE6(LAT);
        c.setLngE6(LNG);
        DataScopeContext.executeWithoutScope(() -> communityMapper.insert(c));

        String storeA = storeOf("G0001");
        String storeB = storeOf("G0003");
        assertThat(storeA).as("夹具：两件货要属于不同商家，否则拆不出两张子单").isNotEqualTo(storeB);
        String pointA = pickupFor(storeA, c.getCommunityNo(), 100);
        String pointB = pickupFor(storeB, c.getCommunityNo(), 200);

        try {
            String token = TestLogin.consumer(mvc(), json, otpStore, "13700137088");
            mvc().perform(post("/mp/user/address").header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"买家\",\"phone\":\"13700137088\",\"province\":\"浙江省\","
                            + "\"city\":\"杭州市\",\"district\":\"西湖区\",\"detail\":\"拆单测试 1 号\","
                            + "\"isDefault\":true,\"tag\":\"家\",\"latE6\":" + LAT
                            + ",\"lngE6\":" + LNG + "}"));

            // **不传 pickupNo** —— 买家没选过自提点，后端按他的地址逐个商家配
            String body = mvc().perform(post("/mp/order/preview")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fulfillment\":\"STORE_PICKUP\",\"items\":["
                                    + "{\"goodsNo\":\"G0001\",\"skuNo\":\"SK0001\",\"qty\":1},"
                                    + "{\"goodsNo\":\"G0003\",\"skuNo\":\"SK0004\",\"qty\":1}]}"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(json.readTree(body).path("code").asInt())
                    .as("不传自提点就下不了自提单 = 买家还得先去挑一个点：%s", body)
                    .isZero();
            /*
             * ★ **付款前就要看得到分组**（判据 4）。
             *
             * 等下单响应才知道「原来要跑两个点」就晚了 —— 那时钱已经付了。
             * 所以预览这条路也得把配到的点算出来，并且带名字：
             * 只给点号的话确认页只能显示一串 PP0001。
             */
            var previewSubs = json.readTree(body).path("data").path("subOrders");
            assertThat(previewSubs.size()).isEqualTo(2);
            var previewPicked = new java.util.ArrayList<String>();
            for (var sub : previewSubs) previewPicked.add(sub.path("pickupNo").asString());
            assertThat(previewPicked)
                    .as("预览不给匹配结果 = 买家付完钱才知道要跑两个点")
                    .containsExactlyInAnyOrder(pointA, pointB);
            for (var sub : previewSubs) {
                assertThat(sub.path("pickupName").asString())
                        .as("只给点号不给名字，确认页只能显示一串 PP0001").isNotBlank();
            }

            String order = mvc().perform(post("/mp/order")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fulfillment\":\"STORE_PICKUP\",\"items\":["
                                    + "{\"goodsNo\":\"G0001\",\"skuNo\":\"SK0001\",\"qty\":1},"
                                    + "{\"goodsNo\":\"G0003\",\"skuNo\":\"SK0004\",\"qty\":1}]}"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(json.readTree(order).path("code").asInt())
                    .as("下单失败：%s", order).isZero();
            var subs = json.readTree(order).path("data").path("subOrders");
            assertThat(subs.size()).as("两个商家要拆成两张子单").isEqualTo(2);

            var picked = new java.util.ArrayList<String>();
            for (var sub : subs) picked.add(sub.path("pickupNo").asString());
            assertThat(picked)
                    .as("两张子单落到同一个点 = 还在把一个 pickupNo 抄进每张子单，"
                            + "而这两家各自只承接自己那个点")
                    .containsExactlyInAnyOrder(pointA, pointB);
        } finally {
            // 改了要还原：种子是全量测试共用的，留几行会让别处莫名其妙红
            DataScopeContext.executeWithoutScope(() -> pickupMapper.delete(
                    Wrappers.<CmtPickupPoint>lambdaQuery()
                            .in(CmtPickupPoint::getPickupNo, java.util.List.of(pointA, pointB))));
            DataScopeContext.executeWithoutScope(() -> communityMapper.delete(
                    Wrappers.<CmtCommunity>lambdaQuery()
                            .eq(CmtCommunity::getCommunityNo, c.getCommunityNo())));
        }
    }
}

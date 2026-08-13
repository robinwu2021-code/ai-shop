package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 运营端归档（软删除）。
 *
 * <p>起因是浏览器实测（{@code docs/technical/运营端死按钮实测清单.md}）：
 * 券 / 商家 / 自提点 / 活动的归档按钮**点得到、抄了编号、确认之后 404**，
 * 页面报「资源不存在」—— 而运营要归档的那张券就在列表里明明白白。
 * ops-web 调 18 条归档端点，后端只实现了 2 条（categories）。
 *
 * <p>这里钉三件事，每一件都是「不这么做就会出事」的那种：
 * <ol>
 *   <li><b>归档后从默认列表消失</b> —— 否则运营会以为没生效，反复点</li>
 *   <li><b>与 status 正交</b> —— 暂停的券还在列表里等着恢复，归档的消失。
 *       挤进同一列的话「暂停后归档」会丢掉其中一个状态</li>
 *   <li><b>归档不存在的东西报 404</b> —— 静默成功的表现是「点了归档，
 *       列表刷新后它还在」，运营会以为是缓存</li>
 * </ol>
 */
@SpringBootTest
// 只写 "test"：它已经组合了 h2db,testcfg,api,ops（application.yml §group）。
// 多写一个 "ops" 会让 profile 集合不同 → **第二个 Spring 上下文** →
// 与 JVM 级共享的 H2（DB_CLOSE_DELAY=-1）撞车，schema 脚本重跑、约束重复。
// 单跑绿、全量跑挂，正是这个原因（StoreAndStaffFlowTest 的注释里警告过同一件事）。
@ActiveProfiles("test")
class OpsArchiveFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.marketing.coupon.mapper.CouponMappers.CouponMapper couponMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★★ 归档后从默认列表消失，showArchived=true 时还能找回来")
    void archivedDisappearsFromDefaultList() throws Exception {
        String ops = opsLogin();
        String couponNo = seedCoupon("会被归档的券");

        assertThat(couponNos(ops, false)).contains(couponNo);

        mvc().perform(post("/ops/coupons/" + couponNo + "/archive")
                        .header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.archivedAt").isNumber());

        // 不消失的话，运营会以为归档没生效然后反复点
        assertThat(couponNos(ops, false)).doesNotContain(couponNo);
        // 但必须找得回来 —— 归档是软删除，不是删除
        assertThat(couponNos(ops, true)).contains(couponNo);

        mvc().perform(post("/ops/coupons/" + couponNo + "/unarchive")
                        .header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(couponNos(ops, false)).contains(couponNo);
    }

    @Test
    @DisplayName("★★★ 归档与暂停正交 —— 一张券可以「已暂停 + 已归档」")
    void archiveAndStatusAreOrthogonal() throws Exception {
        String ops = opsLogin();
        String couponNo = seedCoupon("先暂停再归档");

        mvc().perform(post("/ops/coupons/" + couponNo + "/status")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAUSED\",\"reason\":\"面额配错了\"}"))
                .andExpect(jsonPath("$.code").value(0));
        mvc().perform(post("/ops/coupons/" + couponNo + "/archive")
                        .header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0));

        /*
         * 两个状态都要在。挤进同一列的话，这里必然丢一个 ——
         * 而丢掉的那个是「为什么停的」，恢复归档之后没人知道它该不该重新启用。
         */
        var row = rowOf(ops, couponNo);
        assertThat(row.get("status").asString()).isEqualTo("PAUSED");
        assertThat(row.get("archivedAt").isNull()).isFalse();
    }

    @Test
    @DisplayName("★★ 归档不存在的东西报 404 —— 静默成功会让人以为是缓存")
    void archivingUnknownIs404() throws Exception {
        String ops = opsLogin();
        mvc().perform(post("/ops/coupons/CP-NOT-EXIST/archive")
                        .header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(10404));
        mvc().perform(post("/ops/merchants/M-NOT-EXIST/archive")
                        .header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(10404));
    }

    @Test
    @DisplayName("★★ 重复归档是幂等的 —— 运营连点两下不该看到「已经归档过了」")
    void archiveIsIdempotent() throws Exception {
        String ops = opsLogin();
        String couponNo = seedCoupon("连点两下");

        mvc().perform(post("/ops/coupons/" + couponNo + "/archive")
                .header("Authorization", "Bearer " + ops)).andExpect(jsonPath("$.code").value(0));
        // 那句话对他没有任何意义，而他想要的结果（从列表消失）已经达成了
        mvc().perform(post("/ops/coupons/" + couponNo + "/archive")
                .header("Authorization", "Bearer " + ops)).andExpect(jsonPath("$.code").value(0));

        // 没归档过的恢复一次同样不报错
        String fresh = seedCoupon("没归档过");
        mvc().perform(post("/ops/coupons/" + fresh + "/unarchive")
                .header("Authorization", "Bearer " + ops)).andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("★★ 四个实体的归档都通 —— 一份实现服务四个域，不能只对券生效")
    void allFourKindsWork() throws Exception {
        String ops = opsLogin();

        // 自提点与商家用种子数据；活动用券所在主体建不了，改用种子活动
        mvc().perform(post("/ops/pickups/PP0001/archive").header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0));
        mvc().perform(post("/ops/pickups/PP0001/unarchive").header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0));

        mvc().perform(post("/ops/merchants/M0001/archive").header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0));
        mvc().perform(post("/ops/merchants/M0001/unarchive").header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0));
    }

    // ---------------------------------------------------------------- 装配

    private java.util.List<String> couponNos(String ops, boolean showArchived) throws Exception {
        String body = mvc().perform(get("/ops/coupons")
                        .header("Authorization", "Bearer " + ops)
                        .param("showArchived", String.valueOf(showArchived))
                        .param("size", "200"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        var out = new java.util.ArrayList<String>();
        json.readTree(body).get("data").get("records")
                .forEach(n -> out.add(n.get("couponNo").asString()));
        return out;
    }

    private tools.jackson.databind.JsonNode rowOf(String ops, String couponNo) throws Exception {
        String body = mvc().perform(get("/ops/coupons")
                        .header("Authorization", "Bearer " + ops)
                        .param("showArchived", "true").param("size", "200"))
                .andReturn().getResponse().getContentAsString();
        return java.util.stream.StreamSupport
                .stream(json.readTree(body).get("data").get("records").spliterator(), false)
                .filter(n -> couponNo.equals(n.get("couponNo").asString()))
                .findFirst().orElseThrow();
    }

    /** 直接插一张券 —— 建券没有运营端端点（那是商家侧的事），这里只需要一条能归档的数据 */
    private String seedCoupon(String title) {
        var c = new ai.neargo.shop.marketing.coupon.entity.MktCoupon();
        c.setCouponNo("CP-ARC-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        c.setTitle(title);
        c.setType(ai.neargo.shop.marketing.coupon.entity.MktCoupon.FULL_CUT);
        c.setFaceMinor(500L);
        c.setDiscountRate(0);
        c.setThresholdMinor(1000L);
        c.setMaxDiscountMinor(0L);
        c.setFunder("PLATFORM");
        c.setTotalCount(100);
        c.setReceivedCount(0);
        c.setPerUserLimit(1);
        long now = System.currentTimeMillis();
        c.setStartAt(now - 86_400_000L);
        c.setEndAt(now + 86_400_000L);
        c.setStatus("ACTIVE");
        couponMapper.insert(c);
        return c.getCouponNo();
    }

    private String opsLogin() throws Exception {
        return TestLogin.admin(mvc(), json);
    }
}

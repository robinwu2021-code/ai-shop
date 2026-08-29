package ai.neargo.shop.scenario;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 商家提报新社区 → 运营裁决 → 社区建出来（ADR-013 阶段三）。
 *
 * <p>这条链路补的是一个**死路**：商家开在平台还没开的小区里，覆盖项只能从已有社区
 * 里勾，而「让平台加一个小区」没有入口 —— 只能找 BD 口头说，说完没人知道进展。
 *
 * <p>用例守的是三件容易做错的事：待审的社区**不能**进主表（否则会出现在用户的
 * 选点列表里，点进去什么都没有）、通过时才建社区、驳回必须留下能回给商家的理由。
 */
@SpringBootTest
@ActiveProfiles("test")
class CommunityApplyFlowTest {

    @Autowired
    private ai.neargo.shop.community.service.CommunityAdminService adminService;

    @Autowired
    private ai.neargo.shop.community.service.CommunityService communityService;

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper merchantMapper;

    @Autowired
    private ai.neargo.shop.platform.mapper.PlatformMappers.RegionMapper regionMapper;

    /**
     * 造一条街道区划并返回码。
     *
     * <p>聚落模型（2026-08-22）起，裁决通过时**必须挂 9 位街道/镇码**，
     * 且存在性校验会去 sys_region 查 —— H2 测试库没有区划种子，
     * 不造这一条的话所有「通过」路径都会撞 NOT_FOUND，
     * 而报错看起来像校验坏了，其实是测试数据缺了。
     */
    /**
     * 用自增而不是 nanoTime 取模：同一次运行里造几条就可能撞号，撞了报的是 DuplicateKey，
     * 看着像业务问题。
     *
     * <p><b>基数从 330106000 抬到 330106500</b>：`330106` 这个区划号段被**五个测试类**
     * 共用，而各自都从 `...001` 开始自增 —— `RegionFlowTest` seed 了 330106001、
     * `ServiceAreaFlowTest` 用 330106002/003，这个类第一次调 `street()` 正好也生成
     * 330106001，于是全量跑时撞唯一键 `uk_sys_region_code`。
     *
     * <p>症状是**单独跑绿、全量红**，而报错是 MyBatis 的
     * `JdbcSQLIntegrityConstraintViolationException`，指向 RegionMapper.insert ——
     * 看起来像区划写入坏了，与「谁先 seed 了同一个码」毫无关联。
     * 500 这一段留出足够余量，也把「这一段归谁」写在了代码里。
     */
    private static final int CODE_BASE = 330106500;

    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    private String street() {
        String code = String.valueOf(CODE_BASE + SEQ.incrementAndGet());
        var r = new ai.neargo.shop.platform.entity.SysRegion();
        r.setRegionCode(code);
        r.setParentCode("330106");
        r.setLevel("STREET");
        r.setName("测试街道" + code.substring(6));
        r.setEnabled(true);
        r.setSort(0);
        regionMapper.insert(r);
        return code;
    }

    @Test
    @DisplayName("★★ 官方名录里的村：提报即开通，不进运营队列 —— 那道等待按天算，期间货一个人也看不见")
    void officialVillageOpensWithoutReview() {
        String m = merchant();
        String st = street();
        String village = officialVillage(st, "免审测试村村民委员会", 35_019_806, 110_988_280);

        var vo = adminService.submitApply(m, "免审测试村", null, st, null,
                "VILLAGE", village, null, null);

        assertThat(vo.status()).as("官方村不该停在 PENDING").isEqualTo("APPROVED");
        assertThat(vo.communityNo()).isNotBlank();
        assertThat(communityService.all())
                .anySatisfy(c -> assertThat(c.communityNo()).isEqualTo(vo.communityNo()));
    }

    @Test
    @DisplayName("★ 直开也要带上坐标 —— 没坐标的聚落买家用定位永远搜不到，而它看起来完全正常")
    void openedVillageCarriesCoordinates() {
        String m = merchant();
        String st = street();
        String village = officialVillage(st, "带坐标村村民委员会", 22_695_293, 114_027_370);

        var vo = adminService.submitApply(m, "带坐标村", null, st, null, "VILLAGE", village, null, null);

        var created = communityService.all().stream()
                .filter(c -> c.communityNo().equals(vo.communityNo())).findFirst().orElseThrow();
        assertThat(created.distance()).as("有坐标才算得出距离；这里只验它没被建成空坐标").isNotNull();
    }

    @Test
    @DisplayName("★★ 商家自己补录的村仍然要审 —— 名字是他自己起的，免审等于谁都能凭空造聚落")
    void merchantAddedVillageStillNeedsReview() {
        String m = merchant();
        String st = street();
        String village = officialVillage(st, "商家补录村", null, null);
        // 改成商家补录来源
        var row = regionMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<ai.neargo.shop.platform.entity.SysRegion>lambdaQuery()
                .eq(ai.neargo.shop.platform.entity.SysRegion::getRegionCode, village));
        row.setSource("MERCHANT");
        regionMapper.updateById(row);

        var vo = adminService.submitApply(m, "商家补录村", null, st, null, "VILLAGE", village, null, null);

        assertThat(vo.status()).isEqualTo("PENDING");
        assertThat(vo.communityNo()).isNull();
    }

    /** 官方名录里的一条村（第五级、source=OFFICIAL），可带坐标 */
    private String officialVillage(String streetCode, String name, Integer latE6, Integer lngE6) {
        String code = streetCode + String.format("%03d", SEQ.incrementAndGet());
        var r = new ai.neargo.shop.platform.entity.SysRegion();
        r.setRegionCode(code);
        r.setParentCode(streetCode);
        r.setLevel("VILLAGE");
        r.setName(name);
        r.setSource("OFFICIAL");
        r.setEnabled(true);
        r.setSort(0);
        r.setLatE6(latE6);
        r.setLngE6(lngE6);
        regionMapper.insert(r);
        return code;
    }

    @Test
    @DisplayName("★★ 合并两条重复聚落：商家的经营范围跟着换过去，被并掉的那条关掉而不是删掉")
    void mergeRepointsServiceAreas() {
        String m = merchant();
        String st = street();
        // 同一条街道下两条名字相近、位置只差几十米的 —— 高德给「XX花园」和「XX花园A区」就是这个形状
        var a = adminService.openFromMap(m, "合并测试花园", "测试路 1 号", 22_695_293, 114_027_370, st);
        var b = adminService.openFromMap(m, "另一个名字苑", "测试路 3 号", 22_695_299, 114_027_380, st);

        // 商家把两条都框进了经营范围（他自己也分不清那是同一个地方）
        area(m, a.communityNo());
        area(m, b.communityNo());

        adminService.merge(b.communityNo(), a.communityNo(), "OPS1");

        /*
         * 合并的成败**不看聚落表，看可见性**：商家在这个地方还卖不卖得出去。
         * 只把 cmt_community 关掉而不改 mch_service_area 的话，
         * 他的货在这个小区悄悄消失，而他的设置页看上去一切正常。
         */
        assertThat(merchantQuery.reachableCommunities(m))
                .contains(a.communityNo())
                .doesNotContain(b.communityNo());

        // 被并掉的那条关掉即可：历史订单还指着它，删了那些单据的社区名就查不出来了
        assertThat(communityService.all().stream().map(c -> c.communityNo()).toList())
                .doesNotContain(b.communityNo());

        // 名字要留下来，否则下一次地图联想拿「另一个名字苑」来查重又会建出一条
        assertThat(adminService.communities(null, true, true).stream()
                .filter(c -> c.communityNo().equals(a.communityNo())).findFirst().orElseThrow().name())
                .isEqualTo("合并测试花园");
    }

    @Test
    @DisplayName("★ 疑似重复清单只在同一条街道里比 —— 全国几百个「幸福小区」不是重复，是重名")
    void duplicatesStayWithinStreet() {
        String m = merchant();
        String st1 = street();
        String st2 = street();
        var a = adminService.openFromMap(m, "同名小区", null, 22_695_293, 114_027_370, st1);
        var b = adminService.openFromMap(m, "同名小区", null, 30_279_000, 120_131_000, st2);

        var dups = adminService.duplicates(50);
        assertThat(dups).noneSatisfy(d -> {
            assertThat(d.left().communityNo()).isEqualTo(a.communityNo());
            assertThat(d.right().communityNo()).isEqualTo(b.communityNo());
        });
    }

    @Autowired
    private ai.neargo.shop.spi.user.MerchantQueryPort merchantQuery;

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.ServiceAreaMapper serviceAreaMapper;

    /** 给商家框一条社区级经营范围（自助生效那一档） */
    private void area(String entityNo, String communityNo) {
        var x = new ai.neargo.shop.merchant.entity.MchServiceArea();
        x.setAreaNo(ai.neargo.shop.common.BizKey.next(ai.neargo.shop.common.BizKey.SERVICE_AREA));
        x.setEntityNo(entityNo);
        x.setLevel("COMMUNITY");
        x.setRefCode(communityNo);
        x.setSource("SELF");
        x.setStatus("ACTIVE");
        serviceAreaMapper.insert(x);
    }

    private String merchant() {
        var m = new ai.neargo.shop.merchant.entity.MchEntity();
        m.setEntityNo(ai.neargo.shop.common.BizKey.next(ai.neargo.shop.common.BizKey.MERCHANT));
        m.setName("提报测试店");
        m.setStatus("ACTIVE");
        m.setFulfillmentReach("PICKUP");
        merchantMapper.insert(m);
        return m.getEntityNo();
    }

    @Test
    @DisplayName("★★ 待审的提报不进社区表 —— 进了就会出现在用户的选点列表里，而点进去什么都没有")
    void pendingApplyDoesNotCreateCommunity() {
        String m = merchant();
        var vo = adminService.submitApply(m, "还没开的小区", "文一西路 9 号", null, "我的店就在这儿", null, null, null, null);

        assertThat(vo.status()).isEqualTo("PENDING");
        assertThat(vo.communityNo()).isNull();
        assertThat(communityService.all())
                .noneSatisfy(c -> assertThat(c.name()).isEqualTo("还没开的小区"));
    }

    @Test
    @DisplayName("★★ 通过 → 当场建出社区，并回填单号指过去")
    void approveCreatesCommunity() {
        String m = merchant();
        var vo = adminService.submitApply(m, "批过的小区", "文二西路 8 号", null, null, null, null, null, null);

        var decided = adminService.decideApply(vo.applyNo(), true, street(), null, "OPS1");

        assertThat(decided.status()).isEqualTo("APPROVED");
        assertThat(decided.communityNo()).isNotBlank();
        // 建出来就该能被勾选 —— 否则商家提报通过了却依然看不到它
        assertThat(communityService.all())
                .anySatisfy(c -> assertThat(c.communityNo()).isEqualTo(decided.communityNo()));
    }

    @Test
    @DisplayName("★★ 新建出来的社区没坐标，不能因此挤到选点页第一个 —— 那是离用户最远的那个")
    void newCommunityWithoutCoordsSortsLast() {
        String m = merchant();
        var vo = adminService.submitApply(m, "没坐标的小区", null, null, null, null, null, null, null);
        adminService.decideApply(vo.applyNo(), true, street(), null, "OPS1");

        String newNo = adminService.appliesOf(m).get(0).communityNo();

        /*
         * **算不出距离的不进「附近」** —— 从「排最后」收紧成「不出现」（2026-08-21）。
         *
         * 原先它排在有坐标的后面但仍然可见。可见就有人会选，而「没坐标」意味着
         * 系统根本不知道它在哪 —— 一个杭州用户能在「附近」里选到刚提报的广州社区，
         * 与「把 1056 公里外的点伪装成附近」是同一个错，只是换了个来源。
         * 未知就是未知，不是零米。
         */
        var nearby = communityService.nearby(30_290_000, 120_110_000);
        assertThat(nearby.stream().map(v -> v.communityNo()))
                .as("没坐标的社区不该出现在「附近」——系统并不知道它在哪")
                .doesNotContain(newNo);
        assertThat(nearby.get(0).distance()).isGreaterThan(0);

        /*
         * **但链路不能断**：商家提报审过之后建出来的社区只有名字与区划，
         * 坐标要运营后补（ADR-013 阶段三）。补之前它必须在「全部已开通社区」里看得到，
         * 否则「提报 → 审核通过 → 谁也看不见」，商家会以为审核没过。
         */
        assertThat(communityService.all().stream().map(v -> v.communityNo()))
                .as("坐标补齐前，新社区要能在「全部已开通」里被手动选到")
                .contains(newNo);
    }

    @Test
    @DisplayName("★ 挂到不存在的区划要拦 —— 挂错不报错，只会让这个社区在按区覆盖里永远出不来")
    void approveRejectsUnknownRegion() {
        String m = merchant();
        var vo = adminService.submitApply(m, "区划错的小区", null, null, null, null, null, null, null);

        assertThatThrownBy(() -> adminService.decideApply(vo.applyNo(), true, "999999", null, "OPS1"))
                .isInstanceOf(ai.neargo.shop.common.BizException.class);

        // 拦下之后单子还在待审：半通过（社区没建成、单子却变成已批）是最坏的结果
        assertThat(adminService.appliesOf(m)).singleElement()
                .satisfies(a -> assertThat(a.status()).isEqualTo("PENDING"));
    }

    @Test
    @DisplayName("★ 驳回必须写原因 —— 不写的话商家不知道该改什么，只会原样再提一次")
    void rejectNeedsReason() {
        String m = merchant();
        var vo = adminService.submitApply(m, "要被驳的小区", null, null, null, null, null, null, null);

        assertThatThrownBy(() -> adminService.decideApply(vo.applyNo(), false, null, "  ", "OPS1"))
                .isInstanceOf(ai.neargo.shop.common.BizException.class);

        var done = adminService.decideApply(vo.applyNo(), false, null, "这个小区已经在平台上，叫别的名字", "OPS1");
        assertThat(done.status()).isEqualTo("REJECTED");
        // 理由要回得到商家自己的列表里 —— 否则提报出去等于石沉大海
        assertThat(adminService.appliesOf(m)).singleElement()
                .satisfies(a -> assertThat(a.reason()).contains("已经在平台上"));
    }

    @Test
    @DisplayName("★ 同一家店重复提报同一个名字要拦 —— 两个人各裁一条会建出两个同名社区")
    void duplicatePendingApplyIsRejected() {
        String m = merchant();
        adminService.submitApply(m, "重复提的小区", null, null, null, null, null, null, null);

        assertThatThrownBy(() -> adminService.submitApply(m, "重复提的小区", null, null, null, null, null, null, null))
                .isInstanceOf(ai.neargo.shop.common.BizException.class);
    }

    @Test
    @DisplayName("★ 裁完就是终态 —— 再裁一次意味着同一条提报有两个结论，而通过那次已经建了社区")
    void decidedApplyCannotBeDecidedAgain() {
        String m = merchant();
        var vo = adminService.submitApply(m, "只裁一次的小区", null, null, null, null, null, null, null);
        adminService.decideApply(vo.applyNo(), true, street(), null, "OPS1");

        assertThatThrownBy(() -> adminService.decideApply(vo.applyNo(), false, null, "反悔", "OPS2"))
                .isInstanceOf(ai.neargo.shop.common.BizException.class);
    }
}

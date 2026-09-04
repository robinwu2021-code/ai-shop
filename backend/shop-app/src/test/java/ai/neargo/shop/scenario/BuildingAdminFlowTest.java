package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.community.entity.CmtCommunity;
import ai.neargo.shop.community.mapper.CommunityMappers.CommunityMapper;
import ai.neargo.shop.community.service.CommunityAdminService;
import ai.neargo.shop.community.service.CommunityService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 运营建楼与围栏影响预览（T9）。
 *
 * <p>楼栋这一档的价值全在「站在 3 幢门口要判成 3 幢」——
 * 而它建错了不报错：默认围栏套成小区那个 1000 米，一栋楼就把周围整片罩了进去，
 * 症状是买家在隔壁小区打开 App 看到的是这栋楼的商品池，谁也不会想到是一个默认值。
 */
@SpringBootTest
@ActiveProfiles("test")
class BuildingAdminFlowTest {

    @Autowired
    private CommunityAdminService adminService;
    @Autowired
    private CommunityService communityService;
    @Autowired
    private CommunityMapper communityMapper;
    @Autowired
    private ai.neargo.shop.user.mapper.UserMappers.AddressMapper addressMapper;

    private static int seq = 9100;

    /** 顶层聚落。regionCode 为空时故意不填 —— 「父级没有街道」那条判据要用 */
    private String estate(String regionCode, Integer latE6, Integer lngE6) {
        var c = new CmtCommunity();
        c.setCommunityNo("BA" + seq++);
        c.setName("建楼测试小区" + seq);
        c.setStatus("OPEN");
        c.setRegionCode(regionCode);
        c.setKind(CmtCommunity.KIND_ESTATE);
        c.setFenceRadius(1000);
        c.setLatE6(latE6);
        c.setLngE6(lngE6);
        DataScopeContext.executeWithoutScope(() -> communityMapper.insert(c));
        return c.getCommunityNo();
    }

    @Test
    @DisplayName("★★★ 建出来的楼：街道继承自父级、围栏 150 不是 1000、站在楼下判成这栋楼")
    void buildingInheritsStreetAndGetsItsOwnFence() {
        String estate = estate("330106011", 30_400_000, 120_400_000);
        var vo = adminService.createBuilding("测试园区 3 幢", "3 幢", estate,
                30_400_100, 120_400_100, "OPS-TEST");

        var row = DataScopeContext.executeWithoutScope(() -> communityMapper.selectOne(
                Wrappers.<CmtCommunity>lambdaQuery().eq(CmtCommunity::getCommunityNo, vo.communityNo())));
        assertThat(row.getRegionCode())
                .as("街道没继承下来 = 这栋楼在「按街道覆盖」里归到了别人那儿，没人会发现")
                .isEqualTo("330106011");
        assertThat(row.getParentNo()).isEqualTo(estate);
        assertThat(row.getKind()).isEqualTo(CmtCommunity.KIND_BUILDING);
        assertThat(row.getFenceRadius())
                .as("楼套小区那个 1000 米 = 周围整片都被算成「我在这栋楼里」，"
                        + "而楼栋这一档存在的全部理由就是层级优先于距离")
                .isEqualTo(150);

        // C 端站在楼门口：最内层要是这栋楼，不是它所在的小区
        var loc = communityService.resolve(30_400_100, 120_400_100, false);
        assertThat(loc.innermostNo())
                .as("站在楼下判成了小区 = 楼栋建了等于没建，两者的商品池不同")
                .isEqualTo(vo.communityNo());
    }

    @Test
    @DisplayName("★★★ 归属只做两层：楼底下不许再挂楼")
    void threeLevelsAreRejected() {
        /*
         * 放开一层看着无害，代价是 reachableCommunities 的展开要递归 ——
         * 而递归展开碰上一条坏数据（自己指自己）会挂住整个可见性，
         * 症状是所有商家的货同时消失，且没有报错可看。
         *
         * 单元和户也确实不是服务单位：没有商家按单元框范围，它们属于收货地址的门牌号。
         */
        String estate = estate("330106012", 30_410_000, 120_410_000);
        var building = adminService.createBuilding("测试园区 5 幢", null, estate, null, null, "OPS-TEST");

        assertThatThrownBy(() -> adminService.createBuilding("501 室", null,
                building.communityNo(), null, null, "OPS-TEST"))
                .isInstanceOf(BizException.class)
                // 自定义文案走 args（BizException 的 message 是错误码名），端上直接当提示显示
                .extracting(e -> ((BizException) e).args()[0])
                .asString().contains("两层");
    }

    @Test
    @DisplayName("★★ 父级还没有街道就不许建楼 —— 建出来一样是错的，只是错得更隐蔽")
    void parentWithoutStreetIsRejected() {
        String orphan = estate(null, 30_420_000, 120_420_000);
        assertThatThrownBy(() -> adminService.createBuilding("无街道 1 幢", null, orphan, null, null, "OPS-TEST"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).args()[0])
                .asString().contains("街道");
    }

    @Test
    @DisplayName("★★★ 围栏影响预览 = 改完之后的实际数 —— 预览与现实各算各的，运营就没法用它做决定")
    void fenceImpactMatchesRealityAfterTheChange() {
        /*
         * **判据要钉在围栏判定本身上，不是「预览前后自洽」。**
         *
         * 第一版我写的是「预览的 previewInside == 改完之后的 currentInside」——
         * 两个数都出自同一个函数，那个函数整体算错了它们照样相等：
         * 消融时把半径乘 0.7 塞进预览，这条用例一声不吭地绿着。
         * 一条永远为真的守卫比没有守卫更糟。
         *
         * 现在拿 C 端真正走的那条路（resolve，围栏判定的唯一出口）当量具：
         * 同一条地址，围栏 150 时判不进来、预览也得说 0；放到 1000 时判得进来、预览也得说 1。
         *
         * ⚠️ 它盖住的是**量级上的分歧**（半径算错、圈错边）。真把公式换成
         * 「中点 cos + 111320」那种写法，这条用例是分辨不出来的 —— 消融验过，
         * 两者在这个纬度上只差厘米级，边界上那一圈之外没有任何用例够得着。
         * 防公式漂移靠的是**只有一份** {@code Geo.meters}，不是这条断言。
         */
        String estate = estate("330106013", 30_430_000, 120_430_000);
        // 一条落在 240 米开外、1000 米以内的地址：只有把围栏放大才会圈进来
        int addrLat = 30_432_400;
        int addrLng = 120_430_000;
        String addrId = seedAddress(addrLat, addrLng);
        try {
            adminService.setFence(estate, 150, "OPS-TEST");
            assertThat(communityService.resolve(addrLat, addrLng, false).innermostNo())
                    .as("量具本身要先站得住：150 米时这条地址确实判不进来").isNotEqualTo(estate);
            var preview = adminService.fenceImpact(estate, 1000);
            assertThat(preview.currentRadiusM()).isEqualTo(150);
            assertThat(preview.currentInside())
                    .as("围栏判定说不在圈里，预览却数进来了 = 两套距离算法")
                    .isZero();
            assertThat(preview.previewInside())
                    .as("围栏判定说 1000 米圈得住，预览必须也数得到它 —— "
                            + "对照量非零，否则把预览写成常量 0 也能绿")
                    .isEqualTo(1);

            // 真改，再让围栏判定自己说一次
            adminService.setFence(estate, 1000, "OPS-TEST");
            assertThat(communityService.resolve(addrLat, addrLng, false).innermostNo())
                    .as("预览说会圈进来，改完却没有 = 运营没法拿它做任何决定")
                    .isEqualTo(estate);
            assertThat(adminService.fenceImpact(estate, null).currentInside()).isEqualTo(1);
        } finally {
            // 改了要还原：种子是全量测试共用的，留一行会让别处莫名其妙红
            DataScopeContext.executeWithoutScope(() -> addressMapper.delete(
                    Wrappers.<ai.neargo.shop.user.entity.UsrAddress>lambdaQuery()
                            .eq(ai.neargo.shop.user.entity.UsrAddress::getAddressId, addrId)));
        }
    }

    @Test
    @DisplayName("★★ 没标点的聚落预览给 0/0 **但分母照给** —— 报错会让整页打不开，缺口反而看不见")
    void impactOnUnlocatedCommunityStillReportsTheDenominator() {
        String noCoords = estate("330106014", null, null);
        var vo = adminService.fenceImpact(noCoords, 500);
        assertThat(vo.currentInside()).isZero();
        assertThat(vo.previewInside()).isZero();
        assertThat(vo.previewRadiusM()).isEqualTo(500);
        assertThat(vo.addressesWithCoords())
                .as("分母不给 = 「会多进来 0 户」在一个没几条地址有坐标的库里被读成「改大没用」")
                .isGreaterThanOrEqualTo(0);
    }

    private String seedAddress(int latE6, int lngE6) {
        var a = new ai.neargo.shop.user.entity.UsrAddress();
        a.setAddressId("BA-ADDR-" + seq++);
        a.setUserNo("U-BA-TEST");
        a.setName("围栏预览测试");
        a.setPhone("13900000000");
        a.setDetail("测试地址");
        a.setLatE6(latE6);
        a.setLngE6(lngE6);
        DataScopeContext.executeWithoutScope(() -> addressMapper.insert(a));
        return a.getAddressId();
    }
}

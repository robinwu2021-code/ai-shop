package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.community.entity.CmtCommunity;
import ai.neargo.shop.community.mapper.CommunityMappers.CommunityMapper;
import ai.neargo.shop.merchant.entity.MchServiceArea;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ServiceAreaMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V321 的两列：`cmt_community.parent_no` 与 `mch_service_area.mode`。
 *
 * <p><b>这一批的判据是「存量一个字都不变」。</b>加列本身不难，
 * 难的是加完之后旧数据的行为悄悄换了个样 —— 而那种变化不报错、不崩，
 * 只在某个商家发现自己的货不见了的时候才暴露。
 */
@SpringBootTest
@ActiveProfiles("test")
class CommunityHierarchyTest {

    @Autowired
    private CommunityMapper communityMapper;
    @Autowired
    private ServiceAreaMapper serviceAreaMapper;
    @Autowired
    private ai.neargo.shop.community.service.CommunityService communityService;

    @Test
    @DisplayName("★★★ 不写 mode 的那条 = INCLUDE —— 存量行一条都没被改过，含义不许变")
    void areaWithoutModeDefaultsToInclude() {
        /*
         * **测的是 DDL 默认值，不是 Java 里的赋值。**
         *
         * 线上那 5 条经营范围是在这一列存在之前写下来的，没有人会回头去给它们赋值 ——
         * 它们的含义完全由 `NOT NULL DEFAULT 'INCLUDE'` 决定。
         * 默认值一旦不生效（比如某天有人把它改成 NOT NULL 无默认），
         * 存量商家的范围会从「纳入」变成别的东西，而没有任何报错。
         *
         * 测试库里一条 service_area 都没有（种子不建），所以这里自己造一条：
         * **不设 mode**，走的正是存量行那条路。
         */
        MchServiceArea row = new MchServiceArea();
        row.setAreaNo("AREA-HIER-TEST");
        row.setEntityNo("M0001");
        row.setLevel("COMMUNITY");
        row.setRefCode("C0001");
        row.setStatus("ACTIVE");
        // 刻意不 setMode

        try {
            DataScopeContext.executeWithoutScope(() -> serviceAreaMapper.insert(row));
            var back = DataScopeContext.executeWithoutScope(() -> serviceAreaMapper.selectOne(
                    Wrappers.<MchServiceArea>lambdaQuery().eq(MchServiceArea::getAreaNo, "AREA-HIER-TEST")));
            assertThat(back).isNotNull();
            assertThat(back.getMode())
                    .as("没写 mode 却不是 INCLUDE = 存量那 5 条范围的含义已经变了")
                    .isEqualTo("INCLUDE");
        } finally {
            // 改了要还原：种子是全量测试共用的，留一行会让别处莫名其妙红
            DataScopeContext.executeWithoutScope(() -> serviceAreaMapper.delete(
                    Wrappers.<MchServiceArea>lambdaQuery().eq(MchServiceArea::getAreaNo, "AREA-HIER-TEST")));
        }
    }

    @Test
    @DisplayName("★★★ 有归属的**只能是楼栋** —— 不许凭空猜出一层归属")
    void onlyBuildingsHaveAParent() {
        /*
         * 归属是**声明的**，只能由建档时写入。某处若在拿别的字段猜归属
         * （按名字、按围栏几何、按 region_code 相同），猜错不会报错，
         * 只会让一栋楼悄悄挂到别人的小区下面，而两边的商品池不同。
         *
         * ⚠️ 这一条**原本写的是「全表 parent 都为空」**。那是 V321 加列那一刻的事实，
         * 不是一条不变量：楼栋这一档落地之后它等于「禁止楼栋存在」，
         * 而第一批楼栋（ServiceAreaExcludeFlowTest 的夹具）一进来它就红了 ——
         * 红得对，但指的是它自己过期了，不是被测的东西坏了。
         * 换成「有 parent ⟹ 是楼栋」，猜归属那件事照样当场变红：
         * 迁移或代码给存量小区填一个归属，它的 kind 还是 ESTATE，这里就红。
         */
        var all = DataScopeContext.executeWithoutScope(() ->
                communityMapper.selectList(Wrappers.<CmtCommunity>lambdaQuery()));
        assertThat(all).isNotEmpty();
        assertThat(all).allSatisfy(c -> {
            if (c.getParentNo() != null) {
                assertThat(c.getKind())
                        .as("非楼栋却有归属（%s）= 某处在猜归属", c.getCommunityNo())
                        .isEqualTo(CmtCommunity.KIND_BUILDING);
            }
        });
    }

    @Test
    @DisplayName("★★ 楼栋这一档的常量与匹配用的是同一个字面量")
    void buildingKindConstantIsWiredToMatching() {
        /*
         * `depthOf` 靠它把楼栋判成「更内层」。常量与判定各写一遍字面量的话，
         * 有一天两处不一样 —— 而症状是「站在楼里却匹配到隔壁小区」，
         * 看起来像坐标不准，没人会怀疑是一个拼写。
         */
        assertThat(CmtCommunity.KIND_BUILDING).isEqualTo("BUILDING");
    }

    @Test
    @DisplayName("★★★ 楼栋的归属要**下发到端上** —— 只存不发，选择器就把楼和小区平铺成一锅")
    void parentNoReachesTheClient() {
        /*
         * 加了一列只在库里，是「只写不读」的另一种形状：闸门全绿、界面看不出区别，
         * 而 B 端选择器分不出「阳光里 3 幢」在「阳光里小区」里面 ——
         * 商家要么把两条都勾上（第二条多余），要么只勾了楼、以为整个小区都做了。
         *
         * 判据落在 VO 上而不是实体上：实体那一层由 entity-alignment 守卫管，
         * 这里管的是**它有没有真的走出去**。
         */
        var estate = new CmtCommunity();
        estate.setCommunityNo("HIER-EST");
        estate.setName("归属下发测试小区");
        estate.setStatus("OPEN");
        estate.setRegionCode("330106009");
        estate.setFenceRadius(1000);
        var building = new CmtCommunity();
        building.setCommunityNo("HIER-BLD");
        building.setName("归属下发测试楼栋");
        building.setStatus("OPEN");
        building.setRegionCode("330106009");
        building.setKind(CmtCommunity.KIND_BUILDING);
        building.setParentNo("HIER-EST");
        building.setFenceRadius(150);
        DataScopeContext.executeWithoutScope(() -> {
            communityMapper.insert(estate);
            return communityMapper.insert(building);
        });
        try {
            var vo = communityService.all("330106009").stream()
                    .filter(c -> "HIER-BLD".equals(c.communityNo())).findFirst().orElseThrow();
            assertThat(vo.parentNo())
                    .as("楼栋的归属没下发 = 端上只能把楼和小区平铺，「框了小区就盖住楼」在界面上不成立")
                    .isEqualTo("HIER-EST");
            assertThat(vo.kind()).isEqualTo(CmtCommunity.KIND_BUILDING);
        } finally {
            // 改了要还原：种子是全量测试共用的，留两行会让别处莫名其妙红
            DataScopeContext.executeWithoutScope(() -> communityMapper.delete(
                    Wrappers.<CmtCommunity>lambdaQuery()
                            .in(CmtCommunity::getCommunityNo, "HIER-EST", "HIER-BLD")));
        }
    }
}

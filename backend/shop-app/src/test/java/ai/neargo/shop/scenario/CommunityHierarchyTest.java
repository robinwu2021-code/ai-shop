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
    @DisplayName("★★★ 存量聚落都是顶层（parent 为空）—— 不许凭空多出归属")
    void existingCommunitiesHaveNoParent() {
        /*
         * 归属是**声明的**，只能由建档时写入。加列那一刻全表应当都是 null ——
         * 若有非空值，说明某处在拿别的字段猜归属，而猜错不会报错，
         * 只会让一栋楼悄悄挂到别人的小区下面。
         */
        var all = DataScopeContext.executeWithoutScope(() ->
                communityMapper.selectList(Wrappers.<CmtCommunity>lambdaQuery()));
        assertThat(all).isNotEmpty();
        assertThat(all).allSatisfy(c -> assertThat(c.getParentNo()).isNull());
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
}

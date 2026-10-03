package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.community.entity.CmtCommunity;
import ai.neargo.shop.merchant.entity.MchServiceArea;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运营端看到的商家覆盖明细（T10）。
 *
 * <p><b>这一栏此前对平台上每一家商家都在说假话。</b>它读的是 {@code mch_entity_community}，
 * 而那张表 2026-09-04 查生产是 0 行 —— 经营范围早就搬到了 {@code mch_service_area}（5 条）。
 * 症状不是报错、不是空白，是一行确定的「没有覆盖社区」：运营据此判断
 * 「这家商家还没配范围」，而他配了，只是配在另一张表里。
 */
@SpringBootTest
@ActiveProfiles("test")
class MerchantCoverageDetailTest {

    @Autowired
    private ai.neargo.shop.merchant.service.MerchantGovernService governService;
    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper merchantMapper;
    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper storeMapper;
    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.ServiceAreaMapper areaMapper;
    @Autowired
    private ai.neargo.shop.community.mapper.CommunityMappers.CommunityMapper communityMapper;

    private static int seq = 9300;

    private String community(String parentNo) {
        var c = new CmtCommunity();
        c.setCommunityNo("MC" + seq++);
        c.setName((parentNo == null ? "覆盖明细小区-" : "覆盖明细楼栋-") + seq);
        c.setStatus("OPEN");
        c.setRegionCode("330106021");
        c.setKind(parentNo == null ? CmtCommunity.KIND_ESTATE : CmtCommunity.KIND_BUILDING);
        c.setParentNo(parentNo);
        c.setFenceRadius(parentNo == null ? 1000 : 150);
        DataScopeContext.executeWithoutScope(() -> communityMapper.insert(c));
        return c.getCommunityNo();
    }

    private void area(String entityNo, String refCode, String mode) {
        var a = new MchServiceArea();
        a.setAreaNo(ai.neargo.shop.common.BizKey.next(ai.neargo.shop.common.BizKey.SERVICE_AREA));
        a.setEntityNo(entityNo);
        a.setLevel("COMMUNITY");
        a.setRefCode(refCode);
        a.setSource("SELF");
        a.setStatus("ACTIVE");
        a.setMode(mode);
        DataScopeContext.executeWithoutScope(() -> areaMapper.insert(a));
    }

    @Test
    @DisplayName("★★★ 配在 mch_service_area 里的范围，运营端要看得见 —— 此前这里恒为空")
    void coverageComesFromServiceAreaNotTheDeadTable() {
        String estate = community(null);
        String keep = community(estate);
        String drop = community(estate);

        var m = new ai.neargo.shop.merchant.entity.MchEntity();
        m.setEntityNo(ai.neargo.shop.common.BizKey.next(ai.neargo.shop.common.BizKey.MERCHANT));
        m.setName("覆盖明细测试商家");
        m.setStatus("ACTIVE");
        m.setFulfillmentReach("PICKUP");
        DataScopeContext.executeWithoutScope(() -> merchantMapper.insert(m));
        var st = new ai.neargo.shop.merchant.entity.MchStore();
        st.setStoreNo("MCST" + seq++);
        st.setEntityNo(m.getEntityNo());
        st.setIsDefault(true);
        DataScopeContext.executeWithoutScope(() -> storeMapper.insert(st));

        area(m.getEntityNo(), estate, "INCLUDE");
        area(m.getEntityNo(), drop, "EXCLUDE");

        var cov = governService.storeDetail(st.getStoreNo()).coverage();

        assertThat(cov.includes()).extracting("refCode")
                .as("范围配在 mch_service_area 里，而这一栏读的是那张 0 行的老表 —— "
                        + "于是它对平台上每一家商家都显示「没有覆盖社区」")
                .containsExactly(estate);
        assertThat(cov.excludes()).extracting("refCode")
                .as("排除项混进 includes = 运营会读成「他做这儿」，而事实正好相反")
                .containsExactly(drop);
        assertThat(cov.includes()).extracting("name")
                .as("取不到名要给号，不能留空 —— 空会被读成「没有这一条」")
                .allSatisfy(n -> assertThat((String) n).isNotBlank());

        /*
         * 投影结果：框了什么 ≠ 覆盖到什么。
         * 这里框了小区、排掉一栋楼，实际覆盖的是「小区 + 没被排掉的那栋楼」。
         * 只列「他框了什么」的界面看不出这个差别，而买家看到的是后者。
         */
        assertThat(cov.reachableCount())
                .as("投影只报「他框了几条」= 框一个街道展开成 30 个还是 0 个，界面上完全一样")
                .isEqualTo(2);
        assertThat(cov.reachableSample()).hasSize(2);
        assertThat(cov.reachableSample().toString()).doesNotContain(drop);
        assertThat(cov.reachableSample().toString())
                .as("样本要能看出展开对不对；只给数字的话，30 与 3 一样让人无从判断")
                .isNotBlank();
    }

    @Test
    @DisplayName("★★ 一条范围都没配的商家：三块都空，且投影是 0 —— 那才是真的「没有覆盖」")
    void merchantWithoutAnyAreaReportsZero() {
        var m = new ai.neargo.shop.merchant.entity.MchEntity();
        m.setEntityNo(ai.neargo.shop.common.BizKey.next(ai.neargo.shop.common.BizKey.MERCHANT));
        m.setName("没配范围的商家");
        m.setStatus("ACTIVE");
        m.setFulfillmentReach("PICKUP");
        DataScopeContext.executeWithoutScope(() -> merchantMapper.insert(m));
        var st = new ai.neargo.shop.merchant.entity.MchStore();
        st.setStoreNo("MCST" + seq++);
        st.setEntityNo(m.getEntityNo());
        st.setIsDefault(true);
        DataScopeContext.executeWithoutScope(() -> storeMapper.insert(st));

        var cov = governService.storeDetail(st.getStoreNo()).coverage();
        assertThat(cov.includes()).isEmpty();
        assertThat(cov.excludes()).isEmpty();
        assertThat(cov.reachableCount())
                .as("只自提又没框范围 = 谁也看不到他，这个 0 是真的 —— "
                        + "对照上一条，它证明那条不是「恒为空」蒙对的")
                .isZero();
    }
}

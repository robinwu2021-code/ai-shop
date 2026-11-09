package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.community.entity.CmtCommunity;
import ai.neargo.shop.merchant.service.MerchantStoreService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商家侧范围预览（T14）。
 *
 * <p><b>这一单的判据只有一条：预览说的和保存之后的实际一致。</b>
 * 不一致的症状不是报错 —— 商家看着「会覆盖 12 个聚落」按了保存，
 * 实际覆盖的是另一批，而他要过很久才会从「订单没来」里察觉。
 *
 * <p>所以下面的用例**真的保存一次再查**，不是拿预览跟预览比：
 * 两个数都出自同一个函数的话，那个函数整体算错了它们照样相等（T9 踩过）。
 */
@SpringBootTest
@ActiveProfiles("test")
class ScopePreviewFlowTest {

    @Autowired
    private ai.neargo.shop.spi.user.MerchantQueryPort merchantQuery;
    @Autowired
    private ai.neargo.shop.spi.user.CommunityQueryPort communityQuery;
    @Autowired
    private MerchantStoreService storeService;
    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper merchantMapper;
    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper storeMapper;
    @Autowired
    private ai.neargo.shop.community.mapper.CommunityMappers.CommunityMapper communityMapper;
    @Autowired
    private ai.neargo.shop.user.mapper.UserMappers.AddressMapper addressMapper;

    private static int seq = 9700;

    private String community(String parentNo, Integer latE6, Integer lngE6, int fence) {
        var c = new CmtCommunity();
        c.setCommunityNo("SP" + seq++);
        c.setName((parentNo == null ? "预览测试小区-" : "预览测试楼栋-") + seq);
        c.setStatus("OPEN");
        c.setRegionCode("330106041");
        c.setKind(parentNo == null ? CmtCommunity.KIND_ESTATE : CmtCommunity.KIND_BUILDING);
        c.setParentNo(parentNo);
        c.setFenceRadius(fence);
        c.setLatE6(latE6);
        c.setLngE6(lngE6);
        DataScopeContext.executeWithoutScope(() -> communityMapper.insert(c));
        return c.getCommunityNo();
    }

    private String merchant() {
        var m = new ai.neargo.shop.merchant.entity.MchEntity();
        m.setEntityNo(ai.neargo.shop.common.BizKey.next(ai.neargo.shop.common.BizKey.MERCHANT));
        m.setName("范围预览测试商家");
        m.setStatus("ACTIVE");
        m.setFulfillmentReach("PICKUP");
        DataScopeContext.executeWithoutScope(() -> merchantMapper.insert(m));
        var st = new ai.neargo.shop.merchant.entity.MchStore();
        st.setStoreNo("SPST" + seq++);
        st.setEntityNo(m.getEntityNo());
        st.setIsDefault(true);
        DataScopeContext.executeWithoutScope(() -> storeMapper.insert(st));
        return m.getEntityNo();
    }

    private void save(String m, MerchantStoreService.AreaCommand... areas) {
        storeService.save(m, new MerchantStoreService.SaveCommand(
                null, null, null, null, null, null, null, null, null, null,
                List.of(areas), null, null));
    }

    private static MerchantStoreService.AreaCommand cmd(String ref, String mode) {
        return new MerchantStoreService.AreaCommand("COMMUNITY", ref, mode);
    }

    @Test
    @DisplayName("★★★ 预览说的 = 保存之后真查出来的（含楼栋展开与排除）")
    void previewMatchesWhatSavingActuallyProduces() {
        String estate = community(null, 30_600_000, 120_600_000, 1000);
        String keep = community(estate, 30_600_100, 120_600_000, 150);
        String drop = community(estate, 30_600_900, 120_600_000, 150);
        String m = merchant();

        var next = List.<String[]>of(new String[]{"COMMUNITY", estate, "INCLUDE"},
                new String[]{"COMMUNITY", drop, "EXCLUDE"});
        var preview = merchantQuery.previewReachable(m, next);
        assertThat(preview)
                .as("预览要能看出「框了小区就盖住楼」与「排掉那一栋」")
                .contains(estate, keep).doesNotContain(drop);

        // ★ 真保存，再原样查一次可见性 —— 不是拿预览跟预览比
        save(m, cmd(estate, "INCLUDE"), cmd(drop, "EXCLUDE"));
        assertThat(merchantQuery.reachableCommunities(m))
                .as("预览与保存之后的实际不一致 = 商家照着预览做的决定是错的，"
                        + "而他要从「订单没来」里才察觉")
                .containsExactlyInAnyOrderElementsOf(preview);
    }

    @Test
    @DisplayName("★★★ 预览**不掺库里的旧行** —— 它回答的是「改成这样之后」")
    void previewIgnoresWhatIsAlreadyStored() {
        /*
         * 最容易写错的一处：预览时顺手把库里已有的行也算进去，
         * 于是商家把范围从 A 改成 B，预览显示的是 A ∪ B ——
         * 他看到「覆盖变多了」就放心保存，实际 A 已经没了。
         */
        String a = community(null, 30_610_000, 120_610_000, 500);
        String b = community(null, 30_620_000, 120_620_000, 500);
        String m = merchant();
        save(m, cmd(a, "INCLUDE"));
        assertThat(merchantQuery.reachableCommunities(m)).containsExactly(a);

        var preview = merchantQuery.previewReachable(m, List.<String[]>of(new String[]{"COMMUNITY", b, "INCLUDE"}));
        assertThat(preview)
                .as("预览把库里的旧行也算进来了 = 「改成这样」被算成了「再加上这样」")
                .containsExactly(b);
    }

    @Test
    @DisplayName("★★★ 「那片有几个买家」与 C 端归属是同一批人")
    void buyerCountUsesTheSameAttributionAsTheBuyerSide() {
        /*
         * 商家看到「这片有 1 个买家」就决定要不要做这一片。那个数若与真正
         * 搜得到他的人不是同一批，他的决定就建立在一个看起来很合理的假数上。
         *
         * 坐标摆成「最近的中心不是楼」：小区中心与买家同点，楼中心 120 米外、围栏 150。
         * 按距离取会把这个人算到小区头上，只有「层级优先于距离」才算到楼上。
         */
        String estate = community(null, 30_630_000, 120_630_000, 1000);
        String building = community(estate, 30_631_080, 120_630_000, 150);
        var addrId = "SP-ADDR-" + seq++;
        var addr = new ai.neargo.shop.user.entity.UsrAddress();
        addr.setAddressId(addrId);
        addr.setUserNo("U-SP-TEST");
        addr.setName("预览测试");
        addr.setPhone("13900000000");
        addr.setDetail("测试地址");
        addr.setLatE6(30_630_000);
        addr.setLngE6(120_630_000);
        DataScopeContext.executeWithoutScope(() -> addressMapper.insert(addr));
        try {
            assertThat(communityQuery.buyerCountIn(List.of(building)))
                    .as("这个人被算到小区头上了 = 预览的买家数与 C 端归属不是同一套")
                    .isEqualTo(1);
            assertThat(communityQuery.buyerCountIn(List.of(estate)))
                    .as("对照量：同一个人不能在两处各算一次").isZero();
            assertThat(communityQuery.buyerCountIn(List.of()))
                    .as("空集合是 0，不是全平台").isZero();
        } finally {
            // 改了要还原：种子是全量测试共用的
            DataScopeContext.executeWithoutScope(() -> addressMapper.delete(
                    Wrappers.<ai.neargo.shop.user.entity.UsrAddress>lambdaQuery()
                            .eq(ai.neargo.shop.user.entity.UsrAddress::getAddressId, addrId)));
        }
    }

    /** 约 2 km × 2 km，把 (30.64, 120.64) 附近框进去；顶点经度在前、整数 E6（ADR-034） */
    private static final String SQUARE =
            "[[120630000,30630000],[120650000,30630000],[120650000,30650000],[120630000,30650000]]";

    @Test
    @DisplayName("★★★ 自画范围要算进预览（商户 App 字段对齐 A2），且预览 = 保存之后真查出来的")
    void polygonIsCountedInPreviewAndMatchesSave() {
        String inside = community(null, 30_640_000, 120_640_000, 300);
        String outside = community(null, 30_700_000, 120_700_000, 300);
        String m = merchant();

        var preview = merchantQuery.previewReachable(m,
                List.<String[]>of(new String[]{"POLYGON", null, "INCLUDE", SQUARE}));
        assertThat(preview)
                .as("画了范围预览却是空的 = geometry 在半路被丢掉了")
                .contains(inside).doesNotContain(outside);

        save(m, new MerchantStoreService.AreaCommand("POLYGON", null, "INCLUDE", SQUARE));
        assertThat(merchantQuery.reachableCommunities(m)).containsExactlyInAnyOrderElementsOf(preview);
    }

    @Test
    @DisplayName("★★ 预览里图形不合法回 10473，与保存同一个码（纬度在前、顶点不足）")
    void invalidPolygonInPreviewIs10473() {
        String m = merchant();
        String latFirst = "[[30630000,120630000],[30650000,120630000],[30650000,120650000]]";
        String twoPoints = "[[120630000,30630000],[120650000,30630000]]";
        for (String bad : new String[]{latFirst, twoPoints}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> merchantQuery.previewReachable(m,
                            List.<String[]>of(new String[]{"POLYGON", null, "INCLUDE", bad})))
                    .isInstanceOf(ai.neargo.shop.common.BizException.class)
                    .satisfies(e -> assertThat(((ai.neargo.shop.common.BizException) e).errorCode().code())
                            .isEqualTo(10473));
        }
    }

    @Test
    @DisplayName("★★ 主体还没激活（待补证照）时，图形不合法照样回 10473，不能先回空结果把错误吞掉")
    void invalidPolygonIs10473EvenBeforeEntityIsActive() {
        String m = merchant();
        DataScopeContext.executeWithoutScope(() -> merchantMapper.update(null,
                Wrappers.<ai.neargo.shop.merchant.entity.MchEntity>lambdaUpdate()
                        .eq(ai.neargo.shop.merchant.entity.MchEntity::getEntityNo, m)
                        .set(ai.neargo.shop.merchant.entity.MchEntity::getStatus, "PENDING_LICENSE")));
        // 166 上 App 实测的那一份：两个顶点、纬度在前
        String bad = "[[25033964,121564468],[25033965,121564469]]";
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> merchantQuery.previewReachable(m,
                        List.<String[]>of(new String[]{"POLYGON", null, "INCLUDE", bad})))
                .isInstanceOf(ai.neargo.shop.common.BizException.class)
                .satisfies(e -> assertThat(((ai.neargo.shop.common.BizException) e).errorCode().code())
                        .isEqualTo(10473));
    }

    @Test
    @DisplayName("★★ 只自提 + 预览成空范围 = 谁也看不到，预览要如实给 0")
    void previewOfAnEmptyScopeForPickupOnlyIsZero() {
        String m = merchant();
        assertThat(merchantQuery.previewReachable(m, List.<String[]>of()))
                .as("预览给了个好看的数 = 商家会把范围清空后放心保存，而那等于关店")
                .isEmpty();
    }
}

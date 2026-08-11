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
        var vo = adminService.submitApply(m, "还没开的小区", "文一西路 9 号", null, "我的店就在这儿");

        assertThat(vo.status()).isEqualTo("PENDING");
        assertThat(vo.communityNo()).isNull();
        assertThat(communityService.all())
                .noneSatisfy(c -> assertThat(c.name()).isEqualTo("还没开的小区"));
    }

    @Test
    @DisplayName("★★ 通过 → 当场建出社区，并回填单号指过去")
    void approveCreatesCommunity() {
        String m = merchant();
        var vo = adminService.submitApply(m, "批过的小区", "文二西路 8 号", null, null);

        var decided = adminService.decideApply(vo.applyNo(), true, null, null, "OPS1");

        assertThat(decided.status()).isEqualTo("APPROVED");
        assertThat(decided.communityNo()).isNotBlank();
        // 建出来就该能被勾选 —— 否则商家提报通过了却依然看不到它
        assertThat(communityService.all())
                .anySatisfy(c -> assertThat(c.communityNo()).isEqualTo(decided.communityNo()));
    }

    @Test
    @DisplayName("★ 挂到不存在的区划要拦 —— 挂错不报错，只会让这个社区在按区覆盖里永远出不来")
    void approveRejectsUnknownRegion() {
        String m = merchant();
        var vo = adminService.submitApply(m, "区划错的小区", null, null, null);

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
        var vo = adminService.submitApply(m, "要被驳的小区", null, null, null);

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
        adminService.submitApply(m, "重复提的小区", null, null, null);

        assertThatThrownBy(() -> adminService.submitApply(m, "重复提的小区", null, null, null))
                .isInstanceOf(ai.neargo.shop.common.BizException.class);
    }

    @Test
    @DisplayName("★ 裁完就是终态 —— 再裁一次意味着同一条提报有两个结论，而通过那次已经建了社区")
    void decidedApplyCannotBeDecidedAgain() {
        String m = merchant();
        var vo = adminService.submitApply(m, "只裁一次的小区", null, null, null);
        adminService.decideApply(vo.applyNo(), true, null, null, "OPS1");

        assertThatThrownBy(() -> adminService.decideApply(vo.applyNo(), false, null, "反悔", "OPS2"))
                .isInstanceOf(ai.neargo.shop.common.BizException.class);
    }
}

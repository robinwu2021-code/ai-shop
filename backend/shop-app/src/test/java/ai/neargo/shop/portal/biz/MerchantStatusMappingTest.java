package ai.neargo.shop.portal.biz;

import ai.neargo.shop.platform.dto.OpsVOs.MerchantApplyVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B 端商家状态的**合并映射**：审核状态（申请单）× 经营状态（商家主体）→ 一个词。
 *
 * <p>为什么这段映射值得单独测：库里坚持把这两件事分成两张表 ——
 * 「驳回一份申请」和「封禁一家店」的操作人、审计口径、可逆性全都不同，合并会丢信息。
 * 而 B 端首页要回答的只有一个问题「我现在能不能干活」，所以在**下发这一层**合并。
 *
 * <p>这类「一个概念在不同层本就该有不同表示」的差异，不能靠统一取值来消除
 * （统一了就毁掉分层），只能让映射本身成为一段有名字、有测试的代码 ——
 * 与 {@code OrderStatusView} 同样的处理（见 docs/technical/枚举统一方案.md §2「B 一物多态」）。
 *
 * <p>此前这段映射有名字但没有测试，于是有两条隐含约定谁也说不清对不对：
 * FROZEN 会被折叠、未知状态会被当成 SUSPENDED。这两条现在写死在下面。
 */
class MerchantStatusMappingTest {

    private static MerchantApplyVO apply(String status) {
        return new MerchantApplyVO("MA1", "", "老张粮油店", "PERSONAL",
                "张三", "13800000000", "", "", "COMMUNITY",
                List.of(), List.of(), false, "GROCERY",
                status, null, 0L, 0L,
                // 结构化资质（V79）：本测试只关心状态映射，给空即可
                List.of());
    }

    @Test
    @DisplayName("没申请过是 NONE —— 那不是错误，是「你还没开始」")
    void noApplyIsNone() {
        assertThat(BizMerchantController.applyStatus(null)).isEqualTo("NONE");
    }

    @Test
    @DisplayName("申请单状态按审核阶段映射，PENDING 对商家叫 APPLYING")
    void applyStatusMapping() {
        // 库里叫 PENDING（等着被处理），商家看到的是 APPLYING（我提交了）——
        // 同一件事的两个视角，词不同是对的
        assertThat(BizMerchantController.applyStatus(apply("PENDING"))).isEqualTo("APPLYING");
        assertThat(BizMerchantController.applyStatus(apply("REVIEWING"))).isEqualTo("REVIEWING");
        assertThat(BizMerchantController.applyStatus(apply("REJECTED"))).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("APPROVED 却查不到商家主体 → NONE，让商家能重新提交而不是干等")
    void approvedWithoutEntityFallsBackToNone() {
        /*
         * 这是审核事务只提交了一半的故障态，不是某个业务状态。
         * 报 APPLYING 会让商家一直等一个不会来的结果；返回 NONE 至少能引导重提，
         * 而运营侧的审计日志里查得到那次通过。
         */
        assertThat(BizMerchantController.applyStatus(apply("APPROVED"))).isEqualTo("NONE");
    }

    @Test
    @DisplayName("FROZEN 折叠进 SUSPENDED —— 所以端上契约里不该有 FROZEN")
    void frozenCollapsesIntoSuspended() {
        /*
         * 冻结与封禁对「我现在能不能干活」的答案一样，所以在下发这一层合并。
         * 这条断言是为了挡住一种「修复」：看到 mch_entity.status 有 FROZEN，
         * 就往 shared 的 MerchantStatus 里补一个 FROZEN —— 那个值永远不会被下发，
         * 只会变成一个筛不出东西的死分支。
         */
        assertThat(BizMerchantController.bizStatus("ACTIVE")).isEqualTo("ACTIVE");
        assertThat(BizMerchantController.bizStatus("SUSPENDED")).isEqualTo("SUSPENDED");
        assertThat(BizMerchantController.bizStatus("FROZEN")).isEqualTo("SUSPENDED");
    }

    @Test
    @DisplayName("未知状态一律按 SUSPENDED 兜底 —— 宁可误挡不能误放")
    void unknownStatusFailsClosed() {
        // 将来库里多出一个没人认识的状态时，放错了是让一家本该停业的店继续卖货
        assertThat(BizMerchantController.bizStatus("WHATEVER_NEW")).isEqualTo("SUSPENDED");
        assertThat(BizMerchantController.bizStatus(null)).isEqualTo("SUSPENDED");
    }
}

package ai.neargo.shop.trade.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 状态机的单元测试（U1/U2）—— **本模块的第一个测试**。
 *
 * <p>此前 {@code OrderStateMachine} 被 0 个测试类直接引用：它的迁移规则只有
 * 被业务链路顺带走到的那几条是验过的，而它是「什么能变成什么」的唯一真源。
 * 漏一条的后果是「已取消的单被支付回调改回已支付」这类问题，
 * 而那要到对账日才看得出来。
 *
 * <p>它是纯函数：不需要数据库、不需要 Spring。放在 trade 模块而不是 shop-app，
 * 是为了不把纯规则继续拖进那一层 90 秒的集成测试里。
 */
class OrderStateMachineTest {

    @Nested
    @DisplayName("幂等语义：from == to 一律放行")
    class Idempotent {

        /**
         * 这条语义是**为回调重放设计的**：支付通道会重推同一条消息，
         * 每次都抛错的话，重试会把日志刷满而单子其实是对的。
         *
         * <p>但它也意味着「重复发货不会被状态机拒」—— 本轮踩过这一处：
         * 以为状态机会拦，实际是幂等放行，于是换单号会静默覆盖。
         * 所以这条语义必须被一个用例钉住，改动它的人才知道自己在改什么。
         */
        @Test
        @DisplayName("★ 同状态迁移放行 —— 重复发货因此不会被状态机拦")
        void sameStateIsAllowed() {
            assertThat(OrderStateMachine.canTransit(
                    OrderStateMachine.subOrderGraph(),
                    OrdSubOrder.FULFILLING, OrdSubOrder.FULFILLING)).isTrue();

            assertThatCode(() -> OrderStateMachine.assertSubOrderTransit(
                    OrdSubOrder.COMPLETED, OrdSubOrder.COMPLETED)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("终态自迁移也放行（回调重推终态是常态）")
        void terminalSelfTransitAllowed() {
            assertThatCode(() -> OrderStateMachine.assertOrderTransit(
                    OrdOrder.CANCELLED, OrdOrder.CANCELLED)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("主单：支付之后不再有主单级迁移")
    class OrderGraph {

        @Test
        @DisplayName("WAIT_PAY 可以走向支付/取消/关闭")
        void fromWaitPay() {
            for (String to : Set.of(OrdOrder.PAID, OrdOrder.CANCELLED, OrdOrder.CLOSED)) {
                assertThatCode(() -> OrderStateMachine.assertOrderTransit(OrdOrder.WAIT_PAY, to))
                        .as("WAIT_PAY → %s", to)
                        .doesNotThrowAnyException();
            }
        }

        /**
         * <b>回调乱序时先到的终态胜出。</b>
         * 支付回调与超时关单可能同时在路上：如果 CANCELLED 能被改回 PAID，
         * 就会出现「已退款的单又变成已支付」，而钱已经退出去了。
         */
        @Test
        @DisplayName("★ 已取消不能回到已支付 —— 回调只推进不回退")
        void cancelledCannotGoBackToPaid() {
            assertThatThrownBy(() -> OrderStateMachine.assertOrderTransit(
                    OrdOrder.CANCELLED, OrdOrder.PAID))
                    .isInstanceOf(BizException.class);
        }

        @Test
        @DisplayName("已支付没有主单级去向（后续变化都在子单上）")
        void paidIsTerminalAtOrderLevel() {
            assertThat(OrderStateMachine.orderGraph().get(OrdOrder.PAID)).isEmpty();
        }
    }

    @Nested
    @DisplayName("子单：履约链路")
    class SubOrderGraph {

        @Test
        @DisplayName("正路：待付 → 待履约 → 履约中 → 完成")
        void happyPath() {
            assertThatCode(() -> {
                OrderStateMachine.assertSubOrderTransit(OrdSubOrder.WAIT_PAY, OrdSubOrder.WAIT_FULFILL);
                OrderStateMachine.assertSubOrderTransit(OrdSubOrder.WAIT_FULFILL, OrdSubOrder.FULFILLING);
                OrderStateMachine.assertSubOrderTransit(OrdSubOrder.FULFILLING, OrdSubOrder.COMPLETED);
            }).doesNotThrowAnyException();
        }

        /**
         * 「待付款的单直接发货」在真实世界里发生过：商家看到订单列表就去发货，
         * 而那一单其实还没付款。拦住它是为了别让货先出去。
         *
         * <p><b>这条闸从图移到了入口</b>：{@code WAIT_PAY → FULFILLING} 现在是一条合法边，
         * 因为**支付回调**要用它（服务类履约付款即出码，没有备货发货这一步）。
         * 同一对状态，系统走得通、商家走不通 —— 差别在发起方，而发起方是图表达不了的，
         * 所以分成两个断言方法。
         */
        @Test
        @DisplayName("★ 商家不能把未支付的单推进履约 —— 货先出去了，钱还没收")
        void cannotFulfillBeforePaid() {
            assertThatThrownBy(() -> OrderStateMachine.assertMerchantSubOrderTransit(
                    OrdSubOrder.WAIT_PAY, OrdSubOrder.FULFILLING))
                    .isInstanceOf(BizException.class);
            // 「标记送达」同理：未付款的单一步跳到已完成，钱就永远收不回来了
            assertThatThrownBy(() -> OrderStateMachine.assertMerchantSubOrderTransit(
                    OrdSubOrder.WAIT_PAY, OrdSubOrder.COMPLETED))
                    .isInstanceOf(BizException.class);
        }

        /**
         * 反方向：支付回调**必须**走得通这条边，否则服务类订单付完款卡在「待付款」。
         *
         * <p>和上一条一起看才有意义 —— 只留上一条的话，把边删掉也能过。
         */
        @Test
        @DisplayName("★ 但支付回调可以：服务类履约付款即出码，直接进履约中")
        void paymentCallbackMayFulfillDirectly() {
            assertThatCode(() -> OrderStateMachine.assertSubOrderTransit(
                    OrdSubOrder.WAIT_PAY, OrdSubOrder.FULFILLING)).doesNotThrowAnyException();
        }

        /** 幂等：商家闸不该把「本来就在这个状态」的重放请求也拒了 */
        @Test
        @DisplayName("商家闸对 from == to 仍然放行（重复点击是空操作）")
        void merchantGateStaysIdempotent() {
            assertThatCode(() -> OrderStateMachine.assertMerchantSubOrderTransit(
                    OrdSubOrder.WAIT_PAY, OrdSubOrder.WAIT_PAY)).doesNotThrowAnyException();
            assertThatCode(() -> OrderStateMachine.assertMerchantSubOrderTransit(
                    OrdSubOrder.WAIT_FULFILL, OrdSubOrder.FULFILLING)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("★ 售后可以发生在完成之后 —— 完成不是资金终态")
        void refundAfterCompleted() {
            assertThatCode(() -> OrderStateMachine.assertSubOrderTransit(
                    OrdSubOrder.COMPLETED, OrdSubOrder.REFUNDED)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("已取消/已退款是终态，去不了任何别的状态")
        void terminalsHaveNoExit() {
            var graph = OrderStateMachine.subOrderGraph();
            assertThat(graph.get(OrdSubOrder.CANCELLED)).isEmpty();
            assertThat(graph.get(OrdSubOrder.REFUNDED)).isEmpty();
        }
    }

    @Nested
    @DisplayName("售后：终态三选一")
    class AfterSaleGraph {

        @Test
        @DisplayName("驳回之后可以申诉，申诉可能退也可能关闭")
        void rejectedCanBeAppealed() {
            assertThatCode(() -> {
                OrderStateMachine.assertAfterSaleTransit("REJECTED", "ARBITRATING");
                OrderStateMachine.assertAfterSaleTransit("ARBITRATING", "REFUNDED");
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("★ 已退款不能被改成驳回 —— 钱退出去了就不能反悔")
        void refundedIsFinal() {
            assertThatThrownBy(() -> OrderStateMachine.assertAfterSaleTransit("REFUNDED", "REJECTED"))
                    .isInstanceOf(BizException.class);
        }
    }

    @Nested
    @DisplayName("图本身的完整性")
    class GraphIntegrity {

        /**
         * 迁移表里出现的每个目标状态，**自己也必须是表里的一个键** ——
         * 否则 `graph.getOrDefault(from, Set.of())` 会让它变成一个「进得去出不来」的黑洞，
         * 而这不会报错：单子卡在那个状态里，谁也推不动。
         */
        @Test
        @DisplayName("★ 没有黑洞状态：每个可达状态都在图里有自己的条目")
        void everyTargetIsAKey() {
            for (var graph : Map.of(
                    "order", OrderStateMachine.orderGraph(),
                    "subOrder", OrderStateMachine.subOrderGraph(),
                    "afterSale", OrderStateMachine.afterSaleGraph()).entrySet()) {
                for (var entry : graph.getValue().entrySet()) {
                    for (String to : entry.getValue()) {
                        assertThat(graph.getValue())
                                .as("%s 图里 %s → %s 的目标状态没有自己的条目，会变成进得去出不来的黑洞",
                                        graph.getKey(), entry.getKey(), to)
                                .containsKey(to);
                    }
                }
            }
        }
    }
}

package ai.neargo.shop.trade.order.projection;

import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.contract.ContractOrderStatus;
import ai.neargo.shop.trade.service.OrderStatusView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接线等价矩阵：主单库状态 × 子单库状态（含 null）全枚举。
 *
 * <p>{@link #legacy} 是接线前 {@code OrderStatusView.toContract(sub, main)} 的原样副本 ——
 * 接线后它不再存在于生产代码，留在这里当对照。新旧只允许一处差异：主单 {@code CLOSED} → {@code CANCELLED}。
 */
class OrderStatusProjectorEquivalenceTest {

    private static final List<String> MAINS = List.of(OrdOrder.WAIT_PAY, OrdOrder.WAIT_OFFLINE_PAY,
            OrdOrder.PAID, OrdOrder.CANCELLED, OrdOrder.CLOSED);
    private static final List<String> SUBS = Arrays.asList(null, OrdSubOrder.WAIT_PAY, OrdSubOrder.WAIT_FULFILL,
            OrdSubOrder.FULFILLING, OrdSubOrder.COMPLETED, OrdSubOrder.CANCELLED, OrdSubOrder.REFUNDED);

    /** 接线前的旧实现（原样） */
    private static String legacy(String sub, String main) {
        String base = sub == null ? "WAIT_PAY" : OrdSubOrder.WAIT_FULFILL.equals(sub) ? "PAID" : sub;
        return "WAIT_OFFLINE_PAY".equals(main) && "WAIT_PAY".equals(base) ? "WAIT_OFFLINE_PAY" : base;
    }

    @Test
    @DisplayName("新投影 vs 旧实现：只有主单 CLOSED 一类差异，且差异落在 CANCELLED")
    void onlyClosedDiffers() {
        List<String> unexpected = new ArrayList<>();
        for (String main : MAINS) {
            for (String sub : SUBS) {
                String now = OrderStatusProjector.contract(sub, main);
                String old = legacy(sub, main);
                if (now.equals(old)) {
                    continue;
                }
                if (!(OrdOrder.CLOSED.equals(main) && ContractOrderStatus.CANCELLED.equals(now))) {
                    unexpected.add(main + "×" + sub + ": " + old + "→" + now);
                }
            }
        }
        assertThat(unexpected).as("白名单之外的差异").isEmpty();
    }

    @Test
    @DisplayName("全矩阵输出封闭在 7 个契约值内")
    void closed() {
        for (String main : MAINS) {
            for (String sub : SUBS) {
                assertThat(ContractOrderStatus.ALL).contains(OrderStatusProjector.contract(sub, main));
            }
        }
    }

    @Test
    @DisplayName("接线：OrderStatusView.toContract(sub, main) 与投影逐格一致")
    void viewDelegatesToProjector() {
        for (String main : MAINS) {
            for (String sub : SUBS) {
                assertThat(OrderStatusView.toContract(sub, main)).as(main + "×" + sub)
                        .isEqualTo(OrderStatusProjector.contract(sub, main));
            }
        }
    }
}

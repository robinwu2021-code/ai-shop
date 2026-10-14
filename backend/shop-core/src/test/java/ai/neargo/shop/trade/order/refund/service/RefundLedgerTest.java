package ai.neargo.shop.trade.order.refund.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.order.refund.model.RefundLine;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import ai.neargo.shop.trade.order.refund.model.RefundProgress;
import ai.neargo.shop.trade.order.refund.port.RefundLedgerStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 退款账本（第 5 步对象模型 §3；集群规则 R2）。 */
class RefundLedgerTest {

    /** 内存版存储，语义与 SQL 条件增量一致：超上限返回 false */
    static class FakeStore implements RefundLedgerStore {
        final Map<Long, int[]> itemQty = new HashMap<>();     // itemId → {ordered, refunded}
        long subRefunded;
        long pay = 13500;
        String progress = "NONE";

        public boolean addItemRefund(long itemId, int qty, long minor) {
            int[] q = itemQty.get(itemId);
            if (q[1] + qty > q[0]) {
                return false;
            }
            q[1] += qty;
            return true;
        }

        public boolean addSubRefund(String sub, long minor) {
            if (subRefunded + minor > pay) {
                return false;
            }
            subRefunded += minor;
            return true;
        }

        public long[] subRefundedAndPay(String sub) {
            return new long[]{subRefunded, pay};
        }

        public void setProgress(String sub, String p) {
            progress = p;
        }
    }

    @Test
    @DisplayName("★★ 部分退 → PARTIAL；再退完 → FULL")
    void partialThenFull() {
        FakeStore s = new FakeStore();
        s.itemQty.put(9L, new int[]{3, 0});
        s.itemQty.put(10L, new int[]{1, 0});
        RefundLedger ledger = new RefundLedger(s);

        var r1 = ledger.record(new RefundPlan("S1", List.of(new RefundLine(9, 1, 3000)), 0));
        assertThat(r1.progress()).isEqualTo(RefundProgress.PARTIAL);
        assertThat(s.progress).isEqualTo("PARTIAL");

        var r2 = ledger.record(new RefundPlan("S1", List.of(new RefundLine(9, 2, 6000), new RefundLine(10, 1, 4000)), 500));
        assertThat(r2.progress()).isEqualTo(RefundProgress.FULL);
        assertThat(r2.subRefundedMinor()).isEqualTo(13500);
    }

    @Test
    @DisplayName("★★★ 超退（件数或金额超上限）→ CONFLICT，调用方事务整体回滚")
    void overRefundConflicts() {
        FakeStore s = new FakeStore();
        s.itemQty.put(9L, new int[]{3, 2});
        RefundLedger ledger = new RefundLedger(s);
        assertThatThrownBy(() -> ledger.record(new RefundPlan("S1", List.of(new RefundLine(9, 2, 6000)), 0)))
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.CONFLICT);

        FakeStore money = new FakeStore();
        money.itemQty.put(9L, new int[]{3, 0});
        money.subRefunded = 13000;
        assertThatThrownBy(() -> new RefundLedger(money).record(new RefundPlan("S1", List.of(new RefundLine(9, 1, 3000)), 0)))
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.CONFLICT);
    }
}

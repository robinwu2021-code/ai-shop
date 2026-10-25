package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.entity.FnbKitchenTask;
import ai.neargo.shop.food.policy.KitchenQuantityPolicy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

/** Exercises cumulative quantity boundaries, including loss without fabricated served quantity. */
class KitchenQuantityPolicyTest {
    @Test
    void denyOverServeAndReadyBeforeStart() {
        var task = task("2");
        denied(task, "READY", "1", "0");
        apply(task, "START", "1");
        apply(task, "READY", "1");
        denied(task, "SERVE", "1.000001", "0");
        apply(task, "SERVE", "0.5");
        assertThat(task.getServedQty()).isEqualByComparingTo("0.5");
        assertThat(task.getMakingQty()).isEqualByComparingTo("1");
    }

    @Test
    void voidOnlyUnstartedQuantityAndNeverReleasesStartedCapacity() {
        var task = task("3");
        apply(task, "START", "2");
        denied(task, "VOID", "1.000001", "0");
        apply(task, "VOID", "1");
        denied(task, "START", "0.000001", "0");
        apply(task, "READY", "2");
        apply(task, "SERVE", "2");
        assertThat(task.getStatus()).isEqualTo("SERVED");
        assertThat(task.getQty()).isEqualByComparingTo("3");
        assertThat(task.getVoidQty()).isEqualByComparingTo("1");
    }

    @Test
    void holdBlocksMakingAndUrgeNeverReleasesOrAddsDemand() {
        var task = task("2");
        apply(task, "HOLD", "2");
        denied(task, "START", "1", "0");
        apply(task, "URGE", "1");
        assertThat(task.getStatus()).isEqualTo("HELD");
        assertThat(task.getMakingQty()).isZero();
        assertThat(task.getQty()).isEqualByComparingTo("2");
        apply(task, "FIRE", "2");
        apply(task, "START", "1");
        denied(task, "HOLD", "2", "0");
    }

    @Test
    void lossCannotConsumeServedFoodAndNeverClaimsServed() {
        var task = task("2");
        apply(task, "START", "2");
        apply(task, "READY", "2");
        apply(task, "SERVE", "1");
        denied(task, "LOSS", "1.000001", "0");
        apply(task, "LOSS", "1");
        assertThat(task.getServedQty()).isEqualByComparingTo("1");
        assertThat(task.getReadyQty()).isEqualByComparingTo("2");
        assertThat(task.getStatus()).isEqualTo("VOIDED");
    }

    @Test
    void existingLossSubtractsServeCapacityAndLossCannotRepeat() {
        var task = task("3");
        apply(task, "START", "3");
        apply(task, "READY", "3");
        KitchenQuantityPolicy.apply(task, "SERVE", new BigDecimal("2"), BigDecimal.ONE);
        assertThat(task.getServedQty()).isEqualByComparingTo("2");
        denied(task, "LOSS", "1", "1");
        denied(task, "SERVE", "1", "1");
    }

    @Test
    void disposingCookingFoodDoesNotInventReadyOrReduceHistoricalStartedQuantity() {
        var task = task("2");
        apply(task, "START", "2");
        KitchenQuantityPolicy.apply(
                task, "LOSS", new BigDecimal("0.5"), BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(task.getMakingQty()).isEqualByComparingTo("2");
        assertThat(task.getReadyQty()).isZero();
        assertThat(task.getServedQty()).isZero();
        assertThatThrownBy(
                        () ->
                                KitchenQuantityPolicy.apply(
                                        task,
                                        "READY",
                                        new BigDecimal("2"),
                                        new BigDecimal("0.5"),
                                        BigDecimal.ZERO))
                .isInstanceOf(BizException.class);
        KitchenQuantityPolicy.apply(
                task, "READY", new BigDecimal("1.5"), new BigDecimal("0.5"), BigDecimal.ZERO);
        KitchenQuantityPolicy.apply(
                task, "SERVE", new BigDecimal("1.5"), new BigDecimal("0.5"), BigDecimal.ZERO);
        assertThat(task.getServedQty()).isEqualByComparingTo("1.5");
        assertThat(task.getStatus()).isEqualTo("VOIDED");
    }

    @Test
    void rejectsZeroNegativeTooPreciseAndOutOfRangeDeltas() {
        for (String value : new String[] {"0", "-1", "0.0000001", "1000000000000"}) {
            assertThatThrownBy(() -> KitchenQuantityPolicy.positive(new BigDecimal(value)))
                    .isInstanceOf(BizException.class);
        }
        KitchenQuantityPolicy.positive(new BigDecimal("0.000001"));
    }

    @Test
    void unknownActionsAndTerminalMutationAreDenied() {
        var task = task("1");
        denied(task, "DELIVERED", "1", "0");
        apply(task, "VOID", "1");
        denied(task, "START", "1", "0");
        assertThat(task.getStatus()).isEqualTo("VOIDED");
    }

    private static void apply(FnbKitchenTask task, String action, String value) {
        KitchenQuantityPolicy.apply(task, action, new BigDecimal(value), BigDecimal.ZERO);
    }

    private static void denied(FnbKitchenTask task, String action, String value, String loss) {
        assertThatThrownBy(
                        () ->
                                KitchenQuantityPolicy.apply(
                                        task, action, new BigDecimal(value), new BigDecimal(loss)))
                .isInstanceOf(BizException.class);
    }

    private static FnbKitchenTask task(String qty) {
        var task = new FnbKitchenTask();
        task.setQty(new BigDecimal(qty));
        task.setMakingQty(BigDecimal.ZERO);
        task.setReadyQty(BigDecimal.ZERO);
        task.setServedQty(BigDecimal.ZERO);
        task.setVoidQty(BigDecimal.ZERO);
        task.setStatus("QUEUED");
        return task;
    }
}

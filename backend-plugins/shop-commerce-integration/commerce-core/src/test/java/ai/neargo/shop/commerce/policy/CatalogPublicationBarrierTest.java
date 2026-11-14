package ai.neargo.shop.commerce.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CatalogPublicationBarrierTest {
    @Test
    void unknownRemoteResultKeepsBarrierAcrossWorkerLeaseExpiry() {
        var queued = CatalogPublicationBarrier.idle().begin("OP1");
        var unknown = queued.observe("OP1", 1, CatalogPublicationBarrier.Result.UNKNOWN, false);
        assertThat(unknown.activeOperationNo()).isEqualTo("OP1");
        assertThatThrownBy(() -> unknown.begin("OP2")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                unknown.observe(
                                        "OP2", 1, CatalogPublicationBarrier.Result.SUCCEEDED, true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void terminalPartialAllowsFullReplacementButDoesNotConfirmVersion() {
        var partial =
                CatalogPublicationBarrier.idle()
                        .begin("OP1")
                        .observe("OP1", 1, CatalogPublicationBarrier.Result.PARTIAL, true);
        assertThat(partial.lastConfirmedGeneration()).isZero();
        assertThat(partial.begin("OP2").generation()).isEqualTo(2);
    }

    @Test
    void menuSuccessWithoutTerminalImagesRemainsBlockedAndLateAckCannotAdvance() {
        var active = CatalogPublicationBarrier.idle().begin("OP1");
        var pending = active.observe("OP1", 1, CatalogPublicationBarrier.Result.SUCCEEDED, false);
        assertThat(pending.lastConfirmedGeneration()).isZero();
        assertThatThrownBy(() -> pending.begin("OP2")).isInstanceOf(IllegalStateException.class);
        var next =
                pending.observe("OP1", 1, CatalogPublicationBarrier.Result.SUCCEEDED, true)
                        .begin("OP2");
        assertThat(next.lastConfirmedGeneration()).isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                next.observe(
                                        "OP1", 1, CatalogPublicationBarrier.Result.SUCCEEDED, true))
                .isInstanceOf(IllegalStateException.class);
    }
}

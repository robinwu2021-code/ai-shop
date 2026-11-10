package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.beauty.dto.PreviewWorkOrderSettlementReq;
import ai.neargo.shop.beauty.service.ServiceSubmissionService;
import ai.neargo.shop.beauty.service.WorkOrderSettlementService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Real source database and original bill owner; financial source replies are explicit fixtures. */
class WorkOrderSettlementIT extends SourceFixture {
    @Override
    ApplicationContextRunner business() {
        return super.business().withUserConfiguration(BillReadExecutionBoundaryFixture.class);
    }

    @Test void repeatedActualBillObservationsKeepFingerprintButNeverCreateCollectionAuthority() {
        business().run(context -> {
            var root = open(context, 1, "OPEN");
            existingOwner("OP", 1);
            var submission = context.getBean(ServiceSubmissionService.class).submitForWorkOrder(
                    "E", "S", "A", root.workOrderNo(),
                    existing(root, root.taskNos().getFirst(), "OP"), "BIND");
            assertThat(submission.status()).isEqualTo("BOUND");
            var service = context.getBean(WorkOrderSettlementService.class);
            var first = service.get("E", "S", "A", root.workOrderNo());
            var second = service.get("E", "S", "A", root.workOrderNo());
            assertThat(first.complete()).isTrue();
            assertThat(second.fingerprint()).isEqualTo(first.fingerprint());
            assertThat(second.sources().getFirst().state()).isEqualTo("PREPAID");
            assertThat(second.canSettle()).isFalse();
            assertThat(second.bill().sources().getFirst().receivedMinor()).isEqualTo(1000);
            var preview = service.preview("E", "S", "A", root.workOrderNo(),
                    new PreviewWorkOrderSettlementReq(first.fingerprint(), "CNY", 0L, 1000L, "CASH"));
            assertThat(preview.executable()).isFalse();
            assertThat(preview.blockingReasons()).contains("SOURCE_ALREADY_PREPAID");
        });
    }
}

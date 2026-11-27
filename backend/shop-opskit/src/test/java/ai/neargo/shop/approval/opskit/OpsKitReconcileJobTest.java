package ai.neargo.shop.approval.opskit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.job.api.JobStatus;
import ai.neargo.opskit.connector.Reconciler.Outcome;
import ai.neargo.opskit.connector.Reconciler.State;
import ai.neargo.opskit.connector.approval.ApprovalClient;
import ai.neargo.shop.job.JobSupport;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 对账作业（ADR-067 A12/C2）：送审没到 OpsKit 的关联要能补投；作业要能被独立调度器调起（JobHandler + JobDeclaration）。
 */
class OpsKitReconcileJobTest {

    static final String TYPE = "shop.store_opening";

    final ApprovalClient client = mock(ApprovalClient.class);
    final ApprovalLinkStore links = mock(ApprovalLinkStore.class);
    final ApprovalOutcomes outcomes = mock(ApprovalOutcomes.class);
    final OpsKitSubmitConsumer submitter = mock(OpsKitSubmitConsumer.class);
    final OpsKitReconcileJob job = new OpsKitReconcileJob(client, links, outcomes, submitter, mock(JobSupport.class));

    static ApprovalLinkStore.Row row(String ref, String status) {
        return new ApprovalLinkStore.Row(TYPE, ref, 7, "shop_store_opening", null, null, status, null, "E107", "Ali", null);
    }

    static Outcome outcome(State s) {
        return new Outcome(s, s == State.NOT_FOUND ? null : "PI1", null, null, null);
    }

    @Test
    void lostSubmissionIsRedrivenThroughTheIdempotentSubmitPath() {
        var lost = row("S1", ApprovalLinkStore.PENDING_SUBMIT);
        when(links.stalePending(OpsKitReconcileJob.SUBMIT_OLDER_THAN_HOURS, OpsKitReconcileJob.PENDING_OLDER_THAN_HOURS,
                OpsKitReconcileJob.BATCH)).thenReturn(List.of(lost));
        when(client.reconcile(TYPE, "S1", "shop_store_opening")).thenReturn(outcome(State.NOT_FOUND));

        var r = job.run(null);

        verify(submitter).deliver(TYPE, "S1", 7L);
        assertThat(r.status()).isEqualTo(JobStatus.SUCCESS);
        assertThat(r.detail()).contains("补投 1");
    }

    @Test
    void submissionThatLandedButWasNeverMarkedIsReplayedToBackfillTheInstance() {
        // 送审 HTTP 成功但 markSubmitted 前进程挂了：OpsKit 里在审，本地仍是 PENDING_SUBMIT。同一幂等键重放 → 回填实例号
        var unmarked = row("S2", ApprovalLinkStore.PENDING_SUBMIT);
        when(links.stalePending(anyInt(), anyInt(), anyInt())).thenReturn(List.of(unmarked));
        when(client.reconcile(TYPE, "S2", "shop_store_opening")).thenReturn(outcome(State.IN_PROGRESS));

        job.run(null);

        verify(submitter).deliver(TYPE, "S2", 7L);
    }

    @Test
    void submittedRowNotFoundInOpsKitIsNotResubmitted() {
        // PENDING = OpsKit 已经回过实例号，查不到是 OpsKit 侧的问题，重投同一幂等键也不会回填本行 —— 只告警交给人
        var submitted = row("S3", ApprovalLinkStore.PENDING);
        when(links.stalePending(anyInt(), anyInt(), anyInt())).thenReturn(List.of(submitted));
        when(client.reconcile(TYPE, "S3", "shop_store_opening")).thenReturn(outcome(State.NOT_FOUND));

        job.run(null);

        verify(outcomes).reconciled(any(), any());
        verify(submitter, never()).deliver(anyString(), anyString(), anyLong());
    }

    @Test
    void finishedInstanceIsClosedNotResubmitted() {
        var unmarked = row("S4", ApprovalLinkStore.PENDING_SUBMIT);
        when(links.stalePending(anyInt(), anyInt(), anyInt())).thenReturn(List.of(unmarked));
        var rejected = outcome(State.REJECTED);
        when(client.reconcile(TYPE, "S4", "shop_store_opening")).thenReturn(rejected);

        job.run(null);

        verify(outcomes).reconciled(unmarked, rejected);
        verify(submitter, never()).deliver(anyString(), anyString(), anyLong());
    }

    @Test
    void oneBrokenRowDoesNotStopTheBatchAndTheRunReportsFailure() {
        var a = row("S5", ApprovalLinkStore.PENDING_SUBMIT);
        var b = row("S6", ApprovalLinkStore.PENDING_SUBMIT);
        when(links.stalePending(anyInt(), anyInt(), anyInt())).thenReturn(List.of(a, b));
        when(client.reconcile(TYPE, "S5", "shop_store_opening")).thenThrow(new IllegalStateException("boom"));
        when(client.reconcile(TYPE, "S6", "shop_store_opening")).thenReturn(outcome(State.NOT_FOUND));

        var r = job.run(null);

        verify(submitter).deliver(TYPE, "S6", 7L);
        assertThat(r.status()).isEqualTo(JobStatus.FAILED);
    }

    @Test
    void lostSendWindowIsShorterThanApprovalWaitButLongerThanOutboxRetries() {
        // 发件箱从第一次失败到转 FAILED 约 72 分钟（OutboxDispatcher.MAX_BACKOFF 注释）
        assertThat(OpsKitReconcileJob.SUBMIT_OLDER_THAN_HOURS * 60).isGreaterThan(72);
        assertThat(OpsKitReconcileJob.SUBMIT_OLDER_THAN_HOURS).isLessThan(OpsKitReconcileJob.PENDING_OLDER_THAN_HOURS);
        assertThat(OpsKitReconcileJob.PENDING_OLDER_THAN_HOURS).isEqualTo(24);
    }

    @Test
    void isDeclaredForTheStandaloneScheduler() {
        assertThat(job.name()).isEqualTo("opskit-approval-reconcile");
        var d = job.opskitApprovalReconcileDeclaration();
        assertThat(d.handlerName()).isEqualTo(job.name());
        assertThat(d.enabled()).isTrue();
    }
}

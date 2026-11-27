package ai.neargo.shop.approval.opskit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.opskit.connector.EventLedger;
import ai.neargo.opskit.connector.approval.ApprovalClient;
import ai.neargo.opskit.platform.api.webhook.SignatureSpec;
import ai.neargo.shop.spi.approval.ApprovalApplyException;
import ai.neargo.shop.spi.approval.ApprovalSubject;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 回调 → 业务的整条链（TDD-OpsKit审批对接 AC4 / AC5 / AC6）：真的 client、真的签名，OpsKit 用 JDK HttpServer 桩代替，
 * 记下执行结果回复（{@code POST /confirmations}）。
 */
class OpsKitApprovalFlowTest {

    static final String SECRET = "oks_TESTTESTTESTTESTTESTTESTTESTTEST";
    static final String TYPE = "shop.store_opening";

    HttpServer opskit;
    final List<String> replies = new ArrayList<>();
    final FakeSubject subject = new FakeSubject();
    final ApprovalLinkStore links = mock(ApprovalLinkStore.class);
    ApprovalClient client;

    @BeforeEach
    void setUp() throws Exception {
        opskit = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        opskit.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String body;
            if (path.endsWith("/confirmations")) {
                replies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                body = "{\"instanceNo\":\"PI1\",\"result\":\"CONFIRM\",\"first\":true}";
            } else if (path.endsWith("/history")) {
                body = "[]";
            } else {
                body = "{}";
            }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        opskit.start();
        var props = new OpsKitProperties(false, "http://127.0.0.1:" + opskit.getAddress().getPort() + "/api/opskit/v1", null,
                "okk_" + "x".repeat(43), SECRET, null, Set.of(TYPE), null);
        var subjects = new ApprovalSubjects(List.of(subject), props);
        var outcomes = new ApprovalOutcomes(subjects, links);
        client = ApprovalClient.builder().baseUrl(props.baseUrl()).workspace(props.workspace())
                .credential(props.credential()).signingSecret(SECRET).ledger(EventLedger.inMemory())
                .subjectPrefixes("shop.").onFinished(outcomes::finished)
                .action(ApprovalSubjects.actionOf(subject), ctx -> outcomes.apply(subject, ctx))
                .build();
    }

    @AfterEach
    void tearDown() {
        opskit.stop(0);
    }

    @Test
    void executionNodeAppliesTheBusinessAndConfirms() {
        subject.version.set(7);
        var r = send("instance.awaiting_confirmation", "PI1:1", awaiting(7));
        assertThat(r).isEqualTo(200);
        assertThat(subject.applied).containsExactly("S1@7");
        assertThat(replies).singleElement().satisfies(b -> assertThat(b).contains("\"result\":\"CONFIRM\""));
    }

    @Test
    void proposalFrozenAtSubmitReachesApply() {
        subject.version.set(7);
        String body = "{\"instanceNo\":\"PI1\",\"processCode\":\"shop_store_opening\",\"subject\":{\"type\":\"shop.store_opening\",\"ref\":\"S1\"},"
                + "\"action\":\"shop.store_opening.apply\",\"token\":\"okc_t1\",\"gateEpoch\":1,"
                + "\"fields\":{\"revision\":7,\"proposal\":{\"cashAmount\":1200,\"restoreCredit\":true}}}";
        send("instance.awaiting_confirmation", "PI1:1", body);
        assertThat(subject.proposals).containsExactly(Map.of("cashAmount", 1200, "restoreCredit", true));
    }

    @Test
    void redeliveryOfTheSameEventIsIgnoredByTheLedger() {
        subject.version.set(7);
        send("instance.awaiting_confirmation", "PI1:1", awaiting(7));
        send("instance.awaiting_confirmation", "PI1:1", awaiting(7));
        assertThat(subject.applied).hasSize(1);
        assertThat(replies).hasSize(1);
    }

    @Test
    void changedObjectIsRejectedAsStale() {
        subject.version.set(8);
        send("instance.awaiting_confirmation", "PI1:1", awaiting(7));
        assertThat(subject.applied).isEmpty();
        assertThat(replies).singleElement().satisfies(b -> assertThat(b).contains("\"result\":\"REJECT\"").contains("\"code\":\"STALE\""));
    }

    @Test
    void anotherSystemsEventsAreAcknowledgedWithoutTouchingTheBusiness() {
        int status = send("instance.awaiting_confirmation", "PI9:1",
                "{\"instanceNo\":\"PI9\",\"subject\":{\"type\":\"np.refund\",\"ref\":\"R1\"},\"action\":\"np.refund.apply\",\"token\":\"okc_x\",\"gateEpoch\":1}");
        assertThat(status).isEqualTo(200);
        assertThat(subject.applied).isEmpty();
        assertThat(replies).isEmpty();
    }

    @Test
    void rejectionClosesTheLinkAndReturnsTheBusinessToEditable() {
        var row = new ApprovalLinkStore.Row(TYPE, "S1", 7, "shop_store_opening", "AP1", "PI1", "PENDING", null, "E107", "Ali", null);
        when(links.forInstance("PI1", TYPE, "S1")).thenReturn(Optional.of(row));
        when(links.close(eq(TYPE), eq("S1"), eq(7L), eq("PI1"), eq("REJECTED"), any())).thenReturn(true);
        send("instance.transitioned", "PI1:4", "{\"instanceNo\":\"PI1\",\"processCode\":\"shop_store_opening\","
                + "\"subject\":{\"type\":\"shop.store_opening\",\"ref\":\"S1\"},\"transition\":\"rejected_review\",\"to\":\"rejected\","
                + "\"toCategory\":\"CANCELLED\",\"open\":false,\"seq\":4}");
        assertThat(subject.closed).containsExactly("S1@7:REJECTED");
    }

    @Test
    void repeatedFinishDoesNotCloseTwice() {
        var row = new ApprovalLinkStore.Row(TYPE, "S1", 7, "shop_store_opening", "AP1", "PI1", "APPLIED", null, "E107", "Ali", null);
        when(links.forInstance(anyString(), anyString(), anyString())).thenReturn(Optional.of(row));
        when(links.close(anyString(), anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(false);
        send("instance.transitioned", "PI1:5", "{\"instanceNo\":\"PI1\",\"subject\":{\"type\":\"shop.store_opening\",\"ref\":\"S1\"},"
                + "\"transition\":\"rejected_review\",\"to\":\"rejected\",\"toCategory\":\"CANCELLED\",\"seq\":5}");
        assertThat(subject.closed).isEmpty();
        verify(links, never()).markSubmitFailed(anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void reconciliationFillsInALostCallbackOnce() {
        var outcomes = new ApprovalOutcomes(new ApprovalSubjects(List.of(subject),
                new OpsKitProperties(false, "http://x", null, null, null, null, Set.of(TYPE), null)), links);
        var row = new ApprovalLinkStore.Row(TYPE, "S1", 7, "shop_store_opening", "AP1", "PI1", "PENDING", null, "E107", "Ali", null);
        when(links.forInstance("PI1", TYPE, "S1")).thenReturn(Optional.of(row));
        when(links.close(eq(TYPE), eq("S1"), eq(7L), eq("PI1"), eq("REJECTED"), any())).thenReturn(true, false);
        var rejected = new ai.neargo.opskit.connector.Reconciler.Outcome(ai.neargo.opskit.connector.Reconciler.State.REJECTED,
                "PI1", "AP1", "rejected", "CANCELLED");
        outcomes.reconciled(row, rejected);
        outcomes.reconciled(row, rejected);   // 回调随后又到 / 第二天再对一次：不重复回退
        outcomes.reconciled(row, new ai.neargo.opskit.connector.Reconciler.Outcome(
                ai.neargo.opskit.connector.Reconciler.State.IN_PROGRESS, "PI1", "AP1", "review", "IN_PROGRESS"));
        assertThat(subject.closed).containsExactly("S1@7:REJECTED");
    }

    @Test
    void defaultFlowFollowsTheLevelAndAlwaysAllowsSelfApproval() {
        // 一级：审核 72 小时 → 生效
        var general = steps(new LeveledSubject(ApprovalSubject.Level.GENERAL));
        assertThat(general).extracting(m -> m.get("code")).containsExactly("review", "apply");
        assertThat(general.get(0)).containsEntry("dueHours", 72).doesNotContainKey("when");
        // 二级：审核 → 复核（仅当 recheck=true）→ 生效
        var important = steps(new LeveledSubject(ApprovalSubject.Level.IMPORTANT));
        assertThat(important).extracting(m -> m.get("code")).containsExactly("review", "recheck", "apply");
        assertThat(important.get(1)).containsEntry("when", Map.of("field", "recheck", "op", "TRUE"))
                .containsEntry("approver", Map.of("kind", "PERM", "value", "finance:recheck")).containsEntry("dueHours", 24);
        // 三级：业务审核 → 复核（固定）→ 生效
        var funds = steps(new LeveledSubject(ApprovalSubject.Level.FUNDS));
        assertThat(funds).extracting(m -> m.get("code")).containsExactly("review", "recheck", "apply");
        assertThat(funds.get(0)).containsEntry("name", Map.of("zh", "业务审核"));
        assertThat(funds.get(1)).doesNotContainKey("when");
        assertThat(funds.get(2)).containsEntry("type", "ACTION")
                .containsEntry("action", Map.of("code", "shop.store_opening.apply", "timeoutMinutes", 10));
        for (var level : ApprovalSubject.Level.values()) {
            assertThat(OpsKitProcessRegistrar.defaultTemplate(new LeveledSubject(level), 10).get("rules"))
                    .isEqualTo(Map.of("selfApproval", true, "crossStepExclusive", false));
        }
        var separated = new LeveledSubject(ApprovalSubject.Level.FUNDS) {
            @Override public boolean separateApprovers() { return true; }
        };
        assertThat(OpsKitProcessRegistrar.defaultTemplate(separated, 10).get("rules"))
                .isEqualTo(Map.of("selfApproval", false, "crossStepExclusive", true));
    }

    @Test
    void factsAreCappedAtFiftyWithANote() {
        var many = java.util.stream.IntStream.range(0, 60).mapToObj(i -> ApprovalSubject.Fact.of("f" + i, "v")).toList();
        var facts = OpsKitSubmitConsumer.facts(new ApprovalSubject.Snapshot("t", null, Set.of(), Map.of(), many));
        assertThat(facts).hasSize(50);
        assertThat(facts.get(49).value()).contains("11");
        var change = OpsKitSubmitConsumer.facts(new ApprovalSubject.Snapshot("t", null, Set.of(), Map.of(),
                List.of(ApprovalSubject.Fact.change("营业状态", "待开通", "营业中"))));
        assertThat(change.get(0).before()).isEqualTo("待开通");
        assertThat(change.get(0).value()).isEqualTo("营业中");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> steps(ApprovalSubject s) {
        return (List<Map<String, Object>>) OpsKitProcessRegistrar.defaultTemplate(s, 10).get("steps");
    }

    /** 只改等级与复核人的替身。 */
    static class LeveledSubject implements ApprovalSubject {
        private final Level level;
        LeveledSubject(Level level) { this.level = level; }
        @Override public String subjectType() { return TYPE; }
        @Override public String processCode() { return "shop_store_opening"; }
        @Override public String processName() { return "门店经营开通"; }
        @Override public String approverPerm() { return "merchant:apply:audit"; }
        @Override public String recheckPerm() { return "finance:recheck"; }
        @Override public Level level() { return level; }
        @Override public Snapshot snapshot(String ref) { return new Snapshot("t", null, Set.of(), Map.of()); }
        @Override public void apply(String ref, long revision) { }
        @Override public void onClosed(String ref, long revision, Outcome outcome, String reason) { }
    }

    @Test
    void newTodoNotifiesStaffHoldingTheConcretePermissionOnly() {
        var staff = mock(ai.neargo.shop.spi.platform.OpsStaffPort.class);
        var alerts = mock(ai.neargo.shop.spi.notify.OpsAlertPort.class);
        when(staff.staffNosWithPerm("merchant:apply:audit")).thenReturn(List.of("E1", "E2"));
        var body = "{\"eventId\":\"e\",\"eventType\":\"task.created\",\"accountId\":\"a\",\"workspaceId\":\"w\",\"workspaceExternalRef\":\"PLATFORM\","
                + "\"occurredAt\":1,\"idemKey\":\"T1:created:1\",\"data\":{\"taskNo\":\"T1\",\"processCode\":\"shop_store_opening\","
                + "\"subject\":{\"type\":\"shop.store_opening\",\"ref\":\"S1\",\"display\":{\"title\":\"门店经营开通 · 文三路店\"}},"
                + "\"candidates\":[\"perm:merchant:apply:audit\",\"perm:merchant:apply:*\",\"perm:merchant:*\",\"perm:*\",\"group:x\"]}}";
        new OpsKitTodoNotifier(staff, alerts).taskCreated(ai.neargo.opskit.connector.Envelope.parse(body));
        verify(staff).staffNosWithPerm("merchant:apply:audit");
        verify(staff, never()).staffNosWithPerm("merchant:*");
        verify(alerts).approvalTodo(eq(new ai.neargo.shop.spi.notify.OpsAlertPort.ApprovalTodo("T1", "门店经营开通 · 文三路店", "shop_store_opening")),
                eq(List.of("E1", "E2")), eq("/approvals?tab=todo"));
    }

    @Test
    void ledgerKeysFitTheConsumedTable() {
        assertThat(DbEventLedger.key("instance.awaiting_confirmation", "PI01M83CK0NDZ0PDWPE5FYE5YPWS:12")).hasSize(64);
    }

    private static String awaiting(long revision) {
        return "{\"instanceNo\":\"PI1\",\"processCode\":\"shop_store_opening\",\"subject\":{\"type\":\"shop.store_opening\",\"ref\":\"S1\"},"
                + "\"action\":\"shop.store_opening.apply\",\"token\":\"okc_t1\",\"gateEpoch\":1,\"fields\":{\"revision\":" + revision + "}}";
    }

    private int send(String type, String idemKey, String data) {
        String body = "{\"eventId\":\"ev-" + idemKey + "\",\"eventType\":\"" + type + "\",\"accountId\":\"a\",\"workspaceId\":\"w\","
                + "\"workspaceExternalRef\":\"PLATFORM\",\"occurredAt\":1,\"idemKey\":\"" + idemKey + "\",\"data\":" + data + "}";
        return client.handleWebhook(SignatureSpec.sign(SECRET, System.currentTimeMillis(), body), body).status();
    }

    /** 门店开通的替身：按 version 判 STALE，与 StoreOpeningApprovalSubject 同口径。 */
    static final class FakeSubject implements ApprovalSubject {
        final AtomicLong version = new AtomicLong();
        final List<String> applied = new ArrayList<>();
        final List<String> closed = new ArrayList<>();
        final List<Map<String, Object>> proposals = new ArrayList<>();

        @Override public String subjectType() { return TYPE; }
        @Override public String processCode() { return "shop_store_opening"; }
        @Override public String processName() { return "门店经营开通"; }
        @Override public String approverPerm() { return "merchant:apply:audit"; }
        @Override public Snapshot snapshot(String ref) { return new Snapshot("t", null, Set.of("merchant:E1"), Map.of()); }

        @Override
        public void apply(String ref, long revision) {
            if (version.get() != revision) {
                throw new ApprovalApplyException(ApprovalApplyException.STALE, "changed");
            }
            applied.add(ref + "@" + revision);
        }

        @Override
        public void apply(String ref, long revision, Map<String, Object> proposal) {
            apply(ref, revision);
            proposals.add(proposal);
        }

        @Override
        public void onClosed(String ref, long revision, Outcome outcome, String reason) {
            closed.add(ref + "@" + revision + ":" + outcome);
        }
    }
}

package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.commerce.config.CommerceAfterSaleAutoConfiguration;
import ai.neargo.shop.commerce.dto.AfterSaleContracts;
import ai.neargo.shop.commerce.policy.ChannelIdentity;
import ai.neargo.shop.commerce.policy.ChannelOrderEconomicPolicy;
import ai.neargo.shop.commerce.service.ChannelAfterSaleService;
import ai.neargo.shop.commerce.service.impl.ChannelAfterSaleServiceImpl;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelAfterSaleFundsPort;
import ai.neargo.shop.spi.commerce.ChannelAfterSaleGateway;
import ai.neargo.shop.spi.commerce.ChannelAfterSaleSourcePort;
import ai.neargo.shop.spi.commerce.ChannelInboundHandler;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.trade.IndustryChannelAfterSalePort;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Real CIM persistence/operations with isolated original-source and supplier contracts; no real
 * funds owner is claimed.
 */
class ChannelAfterSaleWorkflowTest {
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    static class Source implements ChannelAfterSaleSourcePort {
        boolean unavailable;
        String quantity = "0.5";
        long amount = 40;
        int calls;
        final Instant authorized = Instant.now();
        final String financial =
                ChannelOrderEconomicPolicy.fingerprint(
                        new ChannelOrderMaintenanceTest().original("R1", 1, "Original"));

        public Owner owner() {
            return Owner.FOOD;
        }

        IndustryChannelAfterSalePort.Decision decision() {
            var ref =
                    new IndustryChannelOrderPort.Reference(
                            new IndustryChannelOrderPort.Scope("MAIN", "E", "S"),
                            "FOOD",
                            "NATIVE",
                            1,
                            "a".repeat(64),
                            "IMPORT");
            return new IndustryChannelAfterSalePort.Decision(
                    ref,
                    "CORE",
                    "SUB",
                    "EXTERNAL",
                    financial,
                    "EXTERNAL_CASE",
                    "DECISION",
                    IndustryChannelAfterSalePort.Action.REFUND_ONLY,
                    "ORIGINAL_POLICY",
                    "CNY",
                    List.of(
                            new IndustryChannelAfterSalePort.Line(
                                    "LINE",
                                    null,
                                    "NATIVE_LINE",
                                    "COMPONENT",
                                    "OPAQUE_ITEM",
                                    quantity,
                                    "KG",
                                    amount,
                                    "ORIGINAL_CORE_ALLOCATION")),
                    amount,
                    "MERCHANT",
                    authorized,
                    "ORIGINAL_CORE_DECISION_RECEIPT");
        }

        String revision() {
            return PricingSnapshotHash.of(decision());
        }

        public Facts capture(Query query) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            calls++;
            if (unavailable) throw new IllegalStateException("Original source unavailable");
            var now = Instant.now();
            return new Facts(
                    query,
                    decision(),
                    revision(),
                    now,
                    now.plusSeconds(25),
                    "ORIGINAL_CORE_READ",
                    "WAITING_FUNDS_OWNER",
                    "WAITING_ORIGINAL_ACTION");
        }
    }

    static class Funds implements ChannelAfterSaleFundsPort {
        boolean unavailable;
        boolean waiting;
        int calls;

        public Facts capture(Query query) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            calls++;
            if (unavailable) throw new IllegalStateException("Independent funds owner unavailable");
            var now = Instant.now();
            return new Facts(
                    query,
                    waiting ? Status.WAITING : Status.AUTHORIZED,
                    waiting ? null : "INDEPENDENT_FUNDS_AUTH",
                    now,
                    now.plusSeconds(25),
                    "INDEPENDENT_FUNDS_RECEIPT");
        }
    }

    static class Gateway implements ChannelAfterSaleGateway {
        int sends;
        int queries;
        boolean timeout = true;
        Runnable during;
        final Instant requested = Instant.now().minusSeconds(60);
        Document document =
                new Document(
                        "REMOTE_ACCOUNT",
                        "REMOTE_STORE",
                        "EXTERNAL",
                        "EXTERNAL_CASE",
                        "AR1",
                        1L,
                        Type.REFUND_ONLY,
                        State.REQUESTED,
                        "CNY",
                        40,
                        List.of(new RequestedLine("LINE", "0.5", "KG", 40)),
                        requested);

        public String profileNo() {
            return "PP";
        }

        public Set<Capability> capabilities() {
            return Set.of(Capability.AFTER_SALE);
        }

        public List<Snapshot> readAfterSales(ChannelOrderGateway.Query scope) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var now = Instant.now();
            return List.of(
                    new Snapshot(
                            document,
                            PricingSnapshotHash.of(document),
                            now,
                            now.plusSeconds(25),
                            "ORIGINAL_SUPPLIER_CASE_READ"));
        }

        public boolean supportsAfterSale(Type type, Action action) {
            return type.name().equals(action.name()) || action == Action.REJECT;
        }

        public Result applyAfterSale(Publication p) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            sends++;
            assertThat(p.fundsAuthorizationRef()).isEqualTo("INDEPENDENT_FUNDS_AUTH");
            if (during != null) {
                var hook = during;
                during = null;
                hook.run();
            }
            return result(p, timeout ? ResultStatus.UNKNOWN : ResultStatus.REFUNDED);
        }

        public Result queryAfterSale(Publication p) {
            queries++;
            return result(p, ResultStatus.REFUNDED);
        }

        Result result(Publication p, ResultStatus status) {
            return new Result(
                    p.operationNo(),
                    p.scope().externalOrderRef(),
                    p.externalCaseRef(),
                    PricingSnapshotHash.of(p),
                    status,
                    status == ResultStatus.UNKNOWN ? null : "SUPPLIER_REFUND_EVIDENCE");
        }
    }

    void run(
            Source source,
            Funds funds,
            Gateway gateway,
            Consumer<CatalogSynchronizationTest.State> check) {
        var fixture = new ChannelOrderMaintenanceTest();
        fixture.run(
                new ChannelOrderMaintenanceTest.Gateway(),
                r -> {
                    var configured =
                            r.withConfiguration(
                                            AutoConfigurations.of(
                                                    CommerceAfterSaleAutoConfiguration.class))
                                    .withBean(ChannelAfterSaleGateway.class, () -> gateway);
                    if (source != null)
                        configured =
                                configured.withBean(ChannelAfterSaleSourcePort.class, () -> source);
                    if (funds != null)
                        configured =
                                configured.withBean(ChannelAfterSaleFundsPort.class, () -> funds);
                    return configured;
                },
                state -> {
                    state.sql()
                            .execute(
                                    "UPDATE cim_authorization_revision SET"
                                        + " granted_scopes='[\"CATALOG\",\"ORDER\",\"AFTER_SALE\"]'");
                    var d = fixture.original("R1", 1, "Original");
                    var now = Instant.now();
                    var snapshot =
                            new ChannelOrderGateway.Snapshot(
                                    d,
                                    now,
                                    now.plusSeconds(25),
                                    "ORIGINAL_ORDER_READ",
                                    PricingSnapshotHash.of(d));
                    var command =
                            new ChannelOrderIngestPort.Command(
                                    "IMPORT", "E", "S", "CON", "CR", "REV1", snapshot, List.of());
                    state.sql()
                            .update(
                                    "UPDATE cim_order_link SET"
                                        + " external_order_key=?,source_order_ref='NATIVE',core_order_ref='CORE',"
                                        + "import_status='CONFIRMED',acceptance_status='CONFIRMED',import_snapshot=?",
                                    ChannelIdentity.hash("EXTERNAL"),
                                    json.writeValueAsString(command));
                    state.sql()
                            .execute(
                                    "INSERT INTO"
                                        + " cim_order_line_link(link_no,entity_no,channel_order_no,"
                                        + "order_revision_no,external_line_ref,component_kind,component_ref,"
                                        + "source_line_ref,core_order_ref,core_item_ref,sale_quantity,"
                                        + "quantity_unit,amount_minor,currency,allocation_evidence_ref)"
                                        + " VALUES('ALLOCATION','E','ORDER','REV1','LINE','MAIN','COMPONENT',"
                                        + "'NATIVE_LINE','CORE','OPAQUE_ITEM',1.25,'KG',100,'CNY',"
                                        + "'ORIGINAL_CORE_ALLOCATION')");
                    state.context()
                            .getBean(ChannelAfterSaleServiceImpl.class)
                            .handle(
                                    new ChannelInboundHandler.Event(
                                            "EVENT",
                                            "E",
                                            "S",
                                            "CON",
                                            "CR",
                                            "PP",
                                            "REMOTE_ACCOUNT",
                                            "REMOTE_STORE",
                                            "AFTER_SALE_CREATED",
                                            "PROVIDER_EVENT",
                                            "EXTERNAL",
                                            "AR1",
                                            now,
                                            "AUTH",
                                            "vault:ref",
                                            "VERIFIED_CALLBACK",
                                            new byte[0]));
                    check.accept(state);
                });
    }

    String caseNo(CatalogSynchronizationTest.State state) {
        return state.sql().queryForObject("SELECT case_no FROM cim_after_sale_link", String.class);
    }

    AfterSaleContracts.ApplyDecision request(Source source) {
        return new AfterSaleContracts.ApplyDecision(0L, "DECISION", source.revision());
    }

    @Test
    void missingIndependentFundsOwnerRemainsWaitingAndNeverSendsSupplierRefund() {
        var source = new Source();
        var gateway = new Gateway();
        run(
                source,
                null,
                gateway,
                s -> {
                    var api = s.context().getBean(ChannelAfterSaleService.class);
                    var worker = s.context().getBean(ChannelAfterSaleServiceImpl.class);
                    var op = api.applyDecision("E", "S", "A", caseNo(s), request(source), "APPLY");
                    assertThat(op.status()).isEqualTo("WAITING_FUNDS");
                    assertThat(worker.recoverDue(10)).isZero();
                    assertThat(gateway.sends).isZero();
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM cim_operation_attempt",
                                                    Integer.class))
                            .isZero();
                    var detail = api.getCase("E", "S", caseNo(s));
                    assertThat(detail.localFundsStatus()).isEqualTo("WAITING_FUNDS_OWNER");
                    assertThat(detail.localInventoryStatus()).isEqualTo("WAITING_ORIGINAL_ACTION");
                });
    }

    @Test
    void originalActionCommitsBeforeSendAndUncertaintyRecoversWithoutOriginalOwners() {
        var source = new Source();
        var funds = new Funds();
        var gateway = new Gateway();
        run(
                source,
                funds,
                gateway,
                s -> {
                    var api = s.context().getBean(ChannelAfterSaleService.class);
                    var worker = s.context().getBean(ChannelAfterSaleServiceImpl.class);
                    var request = request(source);
                    var op = api.applyDecision("E", "S", "A", caseNo(s), request, "APPLY");
                    assertThat(gateway.sends).isZero();
                    assertThat(worker.recoverDue(10)).isZero();
                    assertThat(gateway.sends).isOne();
                    source.unavailable = true;
                    funds.unavailable = true;
                    int originalCalls = source.calls;
                    assertThat(api.applyDecision("E", "S", "A", caseNo(s), request, "APPLY"))
                            .isEqualTo(op);
                    s.sql()
                            .execute(
                                    "UPDATE cim_operation SET next_recovery_at='2000-01-01"
                                            + " 00:00:00'");
                    assertThat(worker.recoverDue(10)).isOne();
                    assertThat(gateway.sends).isOne();
                    assertThat(gateway.queries).isOne();
                    assertThat(source.calls).isEqualTo(originalCalls);
                    assertThat(
                                    s.sql()
                                            .queryForList(
                                                    "SELECT attempt_kind FROM cim_operation_attempt"
                                                            + " ORDER BY id",
                                                    String.class))
                            .containsExactly("SEND", "QUERY");
                    var detail = api.getCase("E", "S", caseNo(s));
                    assertThat(detail.decisionStatus()).isEqualTo("SUPPLIER_REFUNDED");
                    assertThat(detail.localFundsStatus()).isEqualTo("WAITING_FUNDS_OWNER");
                    assertThat(detail.localInventoryStatus()).isEqualTo("WAITING_ORIGINAL_ACTION");
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT financial_evidence_status FROM"
                                                            + " cim_order_link",
                                                    String.class))
                            .isEqualTo("UNCONFIRMED");
                });
    }

    @Test
    void missingNativeSourceAndMismatchedOriginalAllocationCannotCreateOperation() {
        var gateway = new Gateway();
        run(
                null,
                new Funds(),
                gateway,
                s -> {
                    assertThatThrownBy(
                                    () ->
                                            s.context()
                                                    .getBean(ChannelAfterSaleService.class)
                                                    .applyDecision(
                                                            "E",
                                                            "S",
                                                            "A",
                                                            caseNo(s),
                                                            new AfterSaleContracts.ApplyDecision(
                                                                    0L, "DECISION", "a".repeat(64)),
                                                            "MISSING"))
                            .isInstanceOf(RuntimeException.class);
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM cim_operation",
                                                    Integer.class))
                            .isZero();
                });
        var source = new Source();
        source.quantity = "1.5";
        source.amount = 120;
        run(
                source,
                new Funds(),
                gateway,
                s -> {
                    assertThatThrownBy(
                                    () ->
                                            s.context()
                                                    .getBean(ChannelAfterSaleService.class)
                                                    .applyDecision(
                                                            "E",
                                                            "S",
                                                            "A",
                                                            caseNo(s),
                                                            request(source),
                                                            "EXCESS"))
                            .isInstanceOf(RuntimeException.class);
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM cim_operation",
                                                    Integer.class))
                            .isZero();
                });
        assertThat(gateway.sends).isZero();
    }

    @Test
    void changedSourceOrRevokedGrantCannotAuthorizeFirstSend() {
        var source = new Source();
        var gateway = new Gateway();
        run(
                source,
                new Funds(),
                gateway,
                s -> {
                    var api = s.context().getBean(ChannelAfterSaleService.class);
                    var worker = s.context().getBean(ChannelAfterSaleServiceImpl.class);
                    api.applyDecision("E", "S", "A", caseNo(s), request(source), "APPLY");
                    source.amount = 41;
                    assertThat(worker.recoverDue(10)).isZero();
                    assertThat(gateway.sends).isZero();
                    source.amount = 40;
                    s.sql().execute("UPDATE cim_account SET active_authorization_no='OTHER'");
                    s.sql()
                            .execute(
                                    "UPDATE cim_operation SET next_recovery_at='2000-01-01"
                                            + " 00:00:00'");
                    assertThat(worker.recoverDue(10)).isZero();
                    assertThat(gateway.sends).isZero();
                });
    }

    @Test
    void lateReceiptOnlyAddsEvidenceAndCannotCrossNewerFenceOrStoreScope() {
        var source = new Source();
        var gateway = new Gateway();
        gateway.timeout = false;
        run(
                source,
                new Funds(),
                gateway,
                s -> {
                    var api = s.context().getBean(ChannelAfterSaleService.class);
                    api.applyDecision("E", "S", "A", caseNo(s), request(source), "APPLY");
                    gateway.during =
                            () ->
                                    s.sql()
                                            .execute(
                                                    "UPDATE cim_operation SET"
                                                        + " fence=fence+1,current_attempt_no=current_attempt_no+1");
                    assertThat(
                                    s.context()
                                            .getBean(ChannelAfterSaleServiceImpl.class)
                                            .recoverDue(10))
                            .isZero();
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT late_evidence_ref FROM"
                                                            + " cim_operation_attempt",
                                                    String.class))
                            .isEqualTo("SUPPLIER_REFUND_EVIDENCE");
                    assertThatThrownBy(() -> api.getCase("E", "OTHER", caseNo(s)))
                            .isInstanceOf(RuntimeException.class);
                    assertThat(api.getCase("E", "S", caseNo(s)).localFundsStatus())
                            .isEqualTo("WAITING_FUNDS_OWNER");
                });
    }
}

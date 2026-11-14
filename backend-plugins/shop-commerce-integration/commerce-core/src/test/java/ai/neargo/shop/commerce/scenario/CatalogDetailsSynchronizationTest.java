package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.commerce.dto.CatalogContracts.CatalogPreviewVO;
import ai.neargo.shop.commerce.dto.CatalogContracts.CreateCatalogScopeReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.PreviewCatalogReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SourceReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SubmitSyncRunReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SyncRunVO;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.commerce.ChannelCatalogGateway;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

import org.junit.jupiter.api.Test;

import java.util.List;

class CatalogDetailsSynchronizationTest {
    CatalogPreviewVO preview(CatalogSynchronizationTest.State state) {
        state.source().detailed = true;
        var scope =
                state.scopes()
                        .createScope(
                                "E",
                                "S",
                                "A",
                                new CreateCatalogScopeReq(
                                        "CON",
                                        "STORE",
                                        "Menu",
                                        List.of(new SourceReq(Owner.FOOD, "MENU", "R1", 0))),
                                "c");
        return state.previews()
                .preview(
                        "E",
                        "S",
                        scope.scopeNo(),
                        "A",
                        new PreviewCatalogReq(scope.version()),
                        "p");
    }

    SyncRunVO submit(CatalogSynchronizationTest.State state, CatalogPreviewVO preview) {
        return state.runs()
                .submit(
                        "E",
                        "S",
                        "A",
                        new SubmitSyncRunReq(
                                preview.scopeNo(),
                                preview.snapshotNo(),
                                preview.hash(),
                                preview.scopeVersion(),
                                "publish"),
                        "submit");
    }

    @Test
    void previewPersistsRealVersionAndOfferCountWhileLegacyAdapterCannotSubmit() {
        new CatalogSynchronizationTest()
                .run(
                        state -> {
                            var preview = preview(state);
                            assertThat(preview.itemCount()).isEqualTo(1);
                            assertThat(preview.document().offers()).isEmpty();
                            assertThat(
                                            state.sql()
                                                    .queryForObject(
                                                            "SELECT schema_version FROM"
                                                                    + " cim_catalog_snapshot WHERE"
                                                                    + " snapshot_no=?",
                                                            Integer.class,
                                                            preview.snapshotNo()))
                                    .isEqualTo(2);
                            assertThatThrownBy(() -> submit(state, preview))
                                    .isInstanceOf(BizException.class);
                            assertThat(
                                            state.sql()
                                                    .queryForObject(
                                                            "SELECT COUNT(*) FROM cim_sync_run",
                                                            Integer.class))
                                    .isZero();
                            assertThat(
                                            state.sql()
                                                    .queryForObject(
                                                            "SELECT COUNT(*) FROM cim_operation",
                                                            Integer.class))
                                    .isZero();
                            assertThat(state.gateway().sends).hasValue(0);
                        });
    }

    @Test
    void explicitVersionTwoAdapterReceivesOriginalExactChoicesWithoutFlattening() {
        new CatalogSynchronizationTest()
                .run(
                        state -> {
                            state.gateway().detailedSupported = true;
                            var run = submit(state, preview(state));
                            state.recovery().recoverDue(20);
                            assertThat(state.gateway().sends).hasValue(1);
                            var document = state.gateway().lastPublication.document();
                            assertThat(document.schemaVersion()).isEqualTo(2);
                            assertThat(
                                            document.details()
                                                    .offers()
                                                    .getFirst()
                                                    .variants()
                                                    .getFirst()
                                                    .bundleGroups()
                                                    .getFirst()
                                                    .choices()
                                                    .getFirst()
                                                    .unitsPerPick())
                                    .isEqualTo("0.333");
                            assertThat(state.runs().getRun("E", "S", run.runNo()).status())
                                    .isEqualTo("SUBMITTED");
                        });
    }

    @Test
    void withdrawnSchemaSupportFailsQueuedWorkBeforeAnyRemoteOperationIsCreated() {
        new CatalogSynchronizationTest()
                .run(
                        state -> {
                            state.gateway().detailedSupported = true;
                            var run = submit(state, preview(state));
                            state.gateway().detailedSupported = false;
                            state.recovery().recoverDue(20);
                            var actual = state.runs().getRun("E", "S", run.runNo());
                            assertThat(actual.status()).isEqualTo("FAILED");
                            assertThat(
                                            state.sql()
                                                    .queryForObject(
                                                            "SELECT failure_code FROM cim_sync_run"
                                                                    + " WHERE run_no=?",
                                                            String.class,
                                                            run.runNo()))
                                    .isEqualTo("SCHEMA_UNSUPPORTED");
                            assertThat(
                                            state.sql()
                                                    .queryForObject(
                                                            "SELECT COUNT(*) FROM cim_operation",
                                                            Integer.class))
                                    .isZero();
                            assertThat(state.gateway().sends).hasValue(0);
                        });
    }

    @Test
    void unsupportedUnknownRecoveryKeepsTheOriginalBarrierAndLaterQueriesInsteadOfResending() {
        new CatalogSynchronizationTest()
                .run(
                        state -> {
                            state.gateway().detailedSupported = true;
                            var preview = preview(state);
                            var run = submit(state, preview);
                            state.gateway().timeout = true;
                            state.recovery().recoverDue(20);
                            var active =
                                    state.scopes()
                                            .getScope("E", "S", preview.scopeNo())
                                            .activeOperationNo();
                            assertThat(active).isNotBlank();
                            assertThat(state.gateway().sends).hasValue(1);
                            state.gateway().detailedSupported = false;
                            due(state, run);
                            state.recovery().recoverDue(20);
                            assertThat(
                                            state.scopes()
                                                    .getScope("E", "S", preview.scopeNo())
                                                    .activeOperationNo())
                                    .isEqualTo(active);
                            assertThat(state.gateway().queries).hasValue(0);
                            assertThat(state.gateway().sends).hasValue(1);
                            state.gateway().detailedSupported = true;
                            state.gateway().timeout = false;
                            state.gateway().queryNext = ChannelCatalogGateway.Outcome.SUCCEEDED;
                            state.gateway().queryTerminal = true;
                            due(state, run);
                            state.recovery().recoverDue(20);
                            assertThat(state.gateway().queries).hasValue(1);
                            assertThat(state.gateway().sends).hasValue(1);
                            assertThat(state.runs().getRun("E", "S", run.runNo()).status())
                                    .isEqualTo("SUCCEEDED");
                        });
    }

    private void due(CatalogSynchronizationTest.State state, SyncRunVO run) {
        state.sql()
                .update(
                        "UPDATE cim_sync_run SET next_recovery_at='2020-01-01 00:00:00' WHERE"
                                + " run_no=?",
                        run.runNo());
        state.sql().update("UPDATE cim_operation SET lease_until='2020-01-01 00:00:00'");
    }

    @Test
    void mismatchedSnapshotMetadataOrTamperedPayloadCannotBeSubmitted() {
        new CatalogSynchronizationTest()
                .run(
                        state -> {
                            state.gateway().detailedSupported = true;
                            var preview = preview(state);
                            state.sql()
                                    .update(
                                            "UPDATE cim_catalog_snapshot SET schema_version=1 WHERE"
                                                    + " snapshot_no=?",
                                            preview.snapshotNo());
                            assertThatThrownBy(() -> submit(state, preview))
                                    .isInstanceOf(BizException.class);
                            state.sql()
                                    .update(
                                            "UPDATE cim_catalog_snapshot SET"
                                                    + " schema_version=2,content_hash=? WHERE"
                                                    + " snapshot_no=?",
                                            "x".repeat(64),
                                            preview.snapshotNo());
                            assertThatThrownBy(() -> submit(state, preview))
                                    .isInstanceOf(BizException.class);
                            assertThat(state.gateway().sends).hasValue(0);
                        });
    }
}

package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import ai.neargo.shop.commerce.dto.CatalogContracts.CreateCatalogScopeReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.PreviewCatalogReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SourceReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SubmitSyncRunReq;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import java.util.List;
import org.junit.jupiter.api.Test;

class CatalogV3AdmissionTest {
    @Test void realPreviewRejectsForeignStoreTaxBeforePersistingSnapshot() {
        new CatalogSynchronizationTest().run(s -> {
            s.source().detailed = true; s.source().evidenceTtlSeconds = 20;
            s.source().documentTransform = d -> CatalogV3PolicyTest.upgrade(d, "OTHER");
            var scope = s.scopes().createScope("E", "S", "A", new CreateCatalogScopeReq("CON", "STORE", "Menu",
                    List.of(new SourceReq(Owner.FOOD, "MENU", "R1", 0))), "create");
            assertThatThrownBy(() -> s.previews().preview("E", "S", scope.scopeNo(), "A",
                    new PreviewCatalogReq(scope.version()), "preview")).isInstanceOf(BizException.class);
            assertThat(s.sql().queryForObject("SELECT COUNT(*) FROM cim_catalog_snapshot", Integer.class)).isZero();
            assertThat(s.gateway().sends.get()).isZero();
        });
    }

    @Test void versionTwoOnlyGatewayCannotSubmitAnOtherwiseValidVersionThreePreview() {
        new CatalogSynchronizationTest().run(s -> {
            s.source().detailed = true; s.source().evidenceTtlSeconds = 20;
            s.source().documentTransform = d -> CatalogV3PolicyTest.upgrade(d, "S");
            s.gateway().detailedSupported = true;
            var scope = s.scopes().createScope("E", "S", "A", new CreateCatalogScopeReq("CON", "STORE", "Menu",
                    List.of(new SourceReq(Owner.FOOD, "MENU", "R1", 0))), "create");
            var preview = s.previews().preview("E", "S", scope.scopeNo(), "A",
                    new PreviewCatalogReq(scope.version()), "preview");
            assertThat(preview.document().schemaVersion()).isEqualTo(3);
            assertThat(preview.itemCount()).isEqualTo(1);
            assertThatThrownBy(() -> s.runs().submit("E", "S", "A", new SubmitSyncRunReq(scope.scopeNo(),
                    preview.snapshotNo(), preview.hash(), preview.scopeVersion(), "send"), "submit"))
                    .isInstanceOf(BizException.class);
            assertThat(s.sql().queryForObject("SELECT COUNT(*) FROM cim_sync_run", Integer.class)).isZero();
            assertThat(s.gateway().sends.get()).isZero();
        });
    }
    @Test void explicitlyCapableGatewayReceivesFrozenVersionThreeWithoutFlatteningItsMetadata() {
        new CatalogSynchronizationTest().run(s -> {
            s.source().detailed = true; s.source().evidenceTtlSeconds = 20;
            s.source().documentTransform = d -> CatalogV3PolicyTest.upgrade(d, "S");
            s.gateway().availabilitySupported = true;
            var scope = s.scopes().createScope("E", "S", "A", new CreateCatalogScopeReq("CON", "STORE", "Menu",
                    List.of(new SourceReq(Owner.FOOD, "MENU", "R1", 0))), "create");
            var preview = s.previews().preview("E", "S", scope.scopeNo(), "A",
                    new PreviewCatalogReq(scope.version()), "preview");
            s.runs().submit("E", "S", "A", new SubmitSyncRunReq(scope.scopeNo(), preview.snapshotNo(),
                    preview.hash(), preview.scopeVersion(), "send"), "submit");
            assertThat(s.gateway().sends.get()).isZero();
            s.recovery().recoverDue(20);
            assertThat(s.gateway().sends.get()).isEqualTo(1);
            assertThat(s.gateway().lastPublication.document().schemaVersion()).isEqualTo(3);
            assertThat(s.gateway().lastPublication.document().availability())
                    .isEqualTo(preview.document().availability());
            assertThat(s.gateway().lastPublication.document().taxBases()).isEqualTo(preview.document().taxBases());
        });
    }

}

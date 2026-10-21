package ai.neargo.shop.food.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.food.dto.StationPublicationSnapshot;
import ai.neargo.shop.food.dto.StationContracts.StationVO;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class StationPublicationDocumentsTest {
    @Test void stationSnapshotRequiresExactScopeAndRouteSkuCoverage() {
        var json = new ObjectMapper();
        var body = new StationPublicationSnapshot(5, "STATION", "S", 0, "E", "A",
                new StationVO("S", 0, "DRAFT", "A", "吧台", "BAR", true, null),
                List.of(new StationPublicationSnapshot.Route("SKU", "MAKE", 1)),
                List.of(new SkuIdentityFact("SKU", "G", "E", "A", 0)), null);
        String text = PublicationDocuments.freeze(json, body);
        assertThat(PublicationDocuments.validate(json, "STATION", "S", text)).isEqualTo(5);
        assertThatThrownBy(() -> PublicationDocuments.validate(json, "STATION", "S",
                text.replace("\"entityNo\":\"E\",\"goodsNo\"", "\"entityNo\":\"FOREIGN\",\"goodsNo\"")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PublicationDocuments.validate(json, "STATION", "S",
                text.replace("\"routeVersion\":1", "\"routeVersion\":0")))
                .isInstanceOf(IllegalStateException.class);
    }
}

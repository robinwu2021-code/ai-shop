package ai.neargo.shop.food.policy;

import ai.neargo.shop.food.dto.StationPublicationSnapshot;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import java.util.HashSet;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/** Validates closed station documents independently of current mutable station or route rows. */
public final class StationPublicationDocuments {
    private StationPublicationDocuments() { }
    public static StationPublicationSnapshot validate(ObjectMapper json, String number, String document) {
        try {
            var body = json.readValue(document, StationPublicationSnapshot.class);
            var station = body.station();
            if (body.contentSchema() != 5 || !"STATION".equals(body.configKind())
                    || !number.equals(body.configNo()) || body.sourceVersion() < 0 || station == null
                    || !number.equals(station.configNo()) || station.version() != body.sourceVersion()
                    || !body.storeNo().equals(station.storeNo()) || !station.enabled()
                    || !MaterialCatalogFacts.validKey(body.entityNo(), 32)
                    || !MaterialCatalogFacts.validKey(body.storeNo(), 32)
                    || body.routes().size() > 1000 || body.skuFacts().size() > 1000) throw malformed();
            var routeKeys = new HashSet<String>(); var expected = new HashSet<String>();
            for (var route : body.routes()) {
                if (!MaterialCatalogFacts.validKey(route.skuNo(), 32) || route.routeVersion() < 1
                        || !List.of("MAKE", "ASSEMBLE", "PACK").contains(route.taskRole())
                        || !routeKeys.add(route.skuNo() + ":" + route.taskRole())) throw malformed();
                expected.add(route.skuNo());
            }
            var actual = new HashSet<String>();
            for (var fact : body.skuFacts()) {
                if (fact == null || !actual.add(fact.skuNo()) || !body.entityNo().equals(fact.entityNo())
                        || !body.storeNo().equals(fact.storeNo()) || fact.version() < 0
                        || !MaterialCatalogFacts.validKey(fact.goodsNo(), 32)) throw malformed();
            }
            if (!expected.equals(actual)) throw malformed();
            return body;
        } catch (RuntimeException failure) { throw new IllegalStateException("Invalid frozen station", failure); }
    }
    private static IllegalStateException malformed() { return new IllegalStateException("Invalid station document"); }
}

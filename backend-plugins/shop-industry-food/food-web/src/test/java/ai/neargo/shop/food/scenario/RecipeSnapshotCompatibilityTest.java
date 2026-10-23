package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.food.policy.PublicationDocuments;
import ai.neargo.shop.food.policy.RecipePublicationDocuments;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Independent wire fixture demonstrates exact recipe numbers before any database writer exists. */
class RecipeSnapshotCompatibilityTest {
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    String recipe() {
        String materialHash = MaterialCatalogFacts.definitionHash("MILK", "E", "Milk", "KG", 1, "OWN");
        return "{\"contentSchema\":3,\"configKind\":\"RECIPE\",\"configNo\":\"R\",\"sourceVersion\":1,"
                + "\"recipe\":{\"recipeNo\":\"R\",\"storeNo\":\"S\",\"skuNo\":\"TEA\",\"recipeCode\":\"DEFAULT\","
                + "\"yieldQty\":\"1\",\"yieldUnit\":\"EA\",\"status\":\"DRAFT\",\"version\":1,\"configVersion\":1,"
                + "\"lines\":[{\"lineNo\":\"L\",\"itemRef\":\"MILK\",\"qty\":\"999999999999.999999\",\"unit\":\"KG\","
                + "\"lossRate\":\"0.123456\",\"materialDefinitionHash\":\"" + materialHash + "\"}],\"adjustments\":[]},"
                + "\"skuFacts\":[{\"skuNo\":\"TEA\",\"goodsNo\":\"G\",\"entityNo\":\"E\","
                + "\"storeNo\":\"S\",\"version\":0}],"
                + "\"materialFacts\":[{\"itemId\":\"MILK\",\"entityNo\":\"E\",\"name\":\"Milk\",\"baseUom\":\"KG\","
                + "\"weighed\":1,\"dataSource\":\"OWN\",\"unitSupport\":\"BASE_ONLY\","
                + "\"postingSupport\":\"INTEGER_ONLY\",\"definitionHash\":\"" + materialHash + "\"}],"
                + "\"optionGroups\":[],\"approvalRef\":null}";
    }
    @Test void schemaThreeCannotOmitDefinitionCountersOrAdjustmentCollections() {
        for (String bad : new String[] {recipe().replace(",\"adjustments\":[]", ""),
                recipe().replace("\"configVersion\":1,", "")}) {
            assertThatThrownBy(() -> PublicationDocuments.validate(json, "RECIPE", "R", bad))
                    .isInstanceOf(RuntimeException.class);
        }
    }
    @Test void recipeSchemaThreeKeepsExactDecimalTextAndCannotBeReadAsBundle() {
        assertThat(RecipePublicationDocuments.optionIdentity("E", "SUGAR", "NO_SUGAR", "SPEC_VALUE", "V1", null))
                .isEqualTo("95c3327b2832db4609d4259ef259b95409868e23dcf393cfa3b1892fb3f238dd");
        assertThat(PublicationDocuments.validate(json, "RECIPE", "R", recipe())).isEqualTo(3);
        for (String bad : new String[] {recipe().replace("\"qty\":\"999999999999.999999\"",
                "\"qty\":999999999999.999999"), recipe().replace("\"sourceVersion\":1", "\"sourceVersion\":1.5"),
                recipe().replace("\"baseUom\":\"KG\"", "\"baseUom\":\"G\""),
                recipe().replace("\"entityNo\":\"E\",\"name\"", "\"entityNo\":\"OTHER\",\"name\"")}) {
            assertThatThrownBy(() -> PublicationDocuments.validate(json, "RECIPE", "R", bad))
                    .isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> PublicationDocuments.validate(json, "BUNDLE", "R", recipe()))
                .isInstanceOf(RuntimeException.class);
    }
}

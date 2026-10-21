package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.food.dto.BundleChoiceInput;
import ai.neargo.shop.food.dto.BundleConfigVO;
import ai.neargo.shop.food.dto.BundleGroupInput;
import ai.neargo.shop.food.dto.BundlePublicationSnapshot;
import ai.neargo.shop.food.dto.ModifierConfigVO;
import ai.neargo.shop.food.dto.PublicationSnapshot;
import ai.neargo.shop.food.policy.PublicationDocuments;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The legacy hash is pinned independently; schema two cannot be interpreted as an older configuration. */
class BundleSnapshotCompatibilityTest {
    final JsonMapper json=JsonMapper.builder().findAndAddModules().build();
    @Test void schemaOneOriginalHashAndBytesRemainReadable() {
        var legacy=new PublicationSnapshot(1,"MODIFIER","MOD",0,new ModifierConfigVO("MOD","ADDON",0,"DRAFT",
                new ModifierConfigVO.Data("珍珠","SKU",null,List.of())),null,List.of(),
                List.of(new SkuIdentityFact("SKU","G","E","S",0)),List.of(),null);
        String bytes=PublicationDocuments.freeze(json,legacy);
        assertThat(PublicationDocuments.hash(json,bytes))
                .isEqualTo("37348b6f5c721d8a2958f9e996f821db66d17878d33377233e54df629f8e56d1");
        assertThat(PublicationDocuments.validate(json,"MODIFIER","MOD",bytes)).isEqualTo(1);
        assertThat(PublicationDocuments.freeze(json,legacy)).isEqualTo(bytes);
        assertThatThrownBy(() -> PublicationDocuments.validate(json,"BUNDLE","MOD",bytes))
                .isInstanceOf(IllegalStateException.class);
    }
    String bundle() {
        var one=BigDecimal.ONE; var zero=BigDecimal.ZERO;
        var definition=new BundleConfigVO("MEAL",3,"DRAFT",new BundleConfigVO.Data(1,null,
                List.of(new BundleGroupInput("G","主食",one,one,null,0)),
                List.of(new BundleChoiceInput("C","G","SKU",new BigDecimal("999999999.999"),"INCLUDED",
                        one,one,one,0))));
        return PublicationDocuments.freeze(json,new BundlePublicationSnapshot(2,"BUNDLE","MEAL",3,definition,
                List.of(new SkuIdentityFact("MEAL","G","E","S",0),new SkuIdentityFact("SKU","G","E","S",0)),null));
    }
    @Test void schemaTwoUsesExactTextQuantitiesAndRejectsUnsupportedFormats() {
        String bytes=bundle();
        assertThat(bytes).contains("\"unitsPerPick\":\"999999999.999\"");
        assertThat(PublicationDocuments.validate(json,"BUNDLE","MEAL",bytes)).isEqualTo(2);
        for(String bad:List.of(bytes.replace("\"contentSchema\":2","\"contentSchema\":3"),
                bytes.replace("\"sourceVersion\":3","\"sourceVersion\":4"),
                bytes.replace("\"sourceVersion\":3","\"sourceVersion\":3.5"),
                bytes.replace("\"unitsPerPick\":\"999999999.999\"","\"unitsPerPick\":999999999.999"),
                bytes.replace("\"configKind\":\"BUNDLE\"","\"configKind\":\"MODIFIER\""))) {
            assertThatThrownBy(() -> PublicationDocuments.validate(json,"BUNDLE","MEAL",bad))
                    .isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> PublicationDocuments.validate(json,"MODIFIER","MEAL",bytes))
                .isInstanceOf(IllegalStateException.class);
    }
}

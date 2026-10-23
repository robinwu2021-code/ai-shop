package ai.neargo.shop.food.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.food.dto.MenuCategoryInput;
import ai.neargo.shop.food.dto.DishVO;
import ai.neargo.shop.food.dto.DishPresentation;
import ai.neargo.shop.food.dto.MeasurementPolicy;
import ai.neargo.shop.food.dto.MenuCompositionVO;
import ai.neargo.shop.food.dto.MenuConfigResultVO;
import ai.neargo.shop.food.dto.MenuContextInput;
import ai.neargo.shop.food.dto.MenuItemInput;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot.Captured;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot.ItemOptions;
import ai.neargo.shop.food.dto.MenuScheduleVO;
import ai.neargo.shop.food.dto.MenuVO;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.GoodsFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MenuPublicationDocumentsTest {
    static Captured captured(String skuScope,List<String> skuNos) {
        var item=new MenuItemInput("ITEM","GOODS",0,true,skuScope,skuNos,"REPLACE",List.of(),List.of());
        var definition=new MenuConfigResultVO(new MenuVO("MENU","STORE","Menu","TIME","UTC",0,null,
                "DRAFT",0L,0L),new MenuScheduleVO(null,null,List.of(),List.of()),
                new MenuCompositionVO(List.of(new MenuCategoryInput("CAT","Category",0,List.of(item)))),
                List.of(new MenuContextInput("POS","DINE_IN",null,null)));
        return new Captured("ENTITY","STORE",definition,List.of(new DishVO("GOODS","SINGLE",0,
                new DishPresentation(null,List.of(),List.of(),List.of(),List.of()),
                new MeasurementPolicy("EA","COUNT",0,null),0,0)),List.of(new ItemOptions("ITEM",List.of())),
                List.of(),List.of());
    }
    static GoodsFact goods(String entity,List<SkuFact> skus) {
        return new GoodsFact("GOODS",entity,"STORE","Goods",null,true,"APPROVED",0,skus);
    }
    @Test void explicitAndAllFreezeOriginalSkuOwnershipWithoutCreatingPriceAuthority() {
        var json=new ObjectMapper();
        var body=new MenuPublicationSnapshot(4,"MENU","MENU",0,captured("ALL",List.of()),
                List.of(new SkuIdentityFact("S1","GOODS","ENTITY","STORE",0),
                        new SkuIdentityFact("S2","GOODS","ENTITY","STORE",0)),
                List.of(goods("ENTITY",List.of(new SkuFact("S1","Small",0),new SkuFact("S2","Large",0)))),null);
        String document=MenuPublicationDocuments.freeze(json,body);
        assertThat(MenuPublicationDocuments.validate(json,"MENU",document)).isEqualTo(4);
        assertThat(document).doesNotContain("\"price\":","\"inventory\":","\"stock\":");
        var missing=new MenuPublicationSnapshot(4,"MENU","MENU",0,body.menu(),body.skuFacts().subList(0,1),
                body.goodsFacts(),null);
        assertThatThrownBy(() -> MenuPublicationDocuments.validate(json,"MENU",
                MenuPublicationDocuments.freeze(json,missing))).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsForeignEntityAndExplicitSkuNotOwnedByGoodsAndUnknownEnvelopeField() {
        var json=new ObjectMapper();
        var body=new MenuPublicationSnapshot(4,"MENU","MENU",0,captured("EXPLICIT",List.of("S1")),
                List.of(new SkuIdentityFact("S1","GOODS","ENTITY","STORE",0)),
                List.of(goods("OTHER",List.of(new SkuFact("S1","Small",0)))),null);
        assertThatThrownBy(() -> MenuPublicationDocuments.validate(json,"MENU",
                MenuPublicationDocuments.freeze(json,body))).isInstanceOf(IllegalStateException.class);
        var mismatched=new MenuPublicationSnapshot(4,"MENU","MENU",0,body.menu(),body.skuFacts(),
                List.of(goods("ENTITY",List.of(new SkuFact("S2","Other",0)))),null);
        assertThatThrownBy(() -> MenuPublicationDocuments.validate(json,"MENU",
                MenuPublicationDocuments.freeze(json,mismatched))).isInstanceOf(IllegalStateException.class);
        var valid=new MenuPublicationSnapshot(4,"MENU","MENU",0,body.menu(),body.skuFacts(),
                List.of(goods("ENTITY",List.of(new SkuFact("S1","Small",0)))),null);
        String doc=MenuPublicationDocuments.freeze(json,valid);
        assertThatThrownBy(() -> MenuPublicationDocuments.validate(json,"MENU",doc.substring(0,doc.length()-1)
                +",\"fakePrice\":0}")).isInstanceOf(IllegalStateException.class);
    }
}

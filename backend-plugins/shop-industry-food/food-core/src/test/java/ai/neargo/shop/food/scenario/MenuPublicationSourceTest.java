package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.dto.DishPresentation;
import ai.neargo.shop.food.dto.PublicationSnapshot;
import ai.neargo.shop.food.dto.OptionGroupConfigVO;
import ai.neargo.shop.food.dto.ModifierConfigVO;
import ai.neargo.shop.food.entity.FnbConfigRevision;
import ai.neargo.shop.food.policy.PublicationDocuments;
import java.math.BigDecimal;
import java.util.Map;
import ai.neargo.shop.food.dto.MeasurementPolicy;
import ai.neargo.shop.food.dto.MenuCategoryInput;
import ai.neargo.shop.food.dto.MenuCompositionVO;
import ai.neargo.shop.food.dto.MenuConfigResultVO;
import ai.neargo.shop.food.dto.MenuContextInput;
import ai.neargo.shop.food.dto.MenuItemInput;
import ai.neargo.shop.food.dto.MenuScheduleVO;
import ai.neargo.shop.food.dto.MenuVO;
import ai.neargo.shop.food.entity.FnbDish;
import ai.neargo.shop.food.entity.FnbDishOptionGroup;
import ai.neargo.shop.food.mapper.FoodMappers.BundleMapper;
import ai.neargo.shop.food.mapper.FoodMappers.DishMapper;
import ai.neargo.shop.food.mapper.FoodMappers.DishOptionGroupMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuChannelBindingMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OptionGroupMapper;
import ai.neargo.shop.food.mapper.FoodMappers.RecipeMapper;
import ai.neargo.shop.food.policy.MenuPublicationSource;
import ai.neargo.shop.food.policy.MenuWorkingDefinitionAccess;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MenuPublicationSourceTest {
    static MenuConfigResultVO definition(String mode) {
        var item=new MenuItemInput("I","G",0,true,"EXPLICIT",List.of("S"),mode,List.of(),List.of());
        return new MenuConfigResultVO(new MenuVO("M","A","Menu","TIME","UTC",0,null,"DRAFT",0L,0L),
                new MenuScheduleVO(null,null,List.of(),List.of()),
                new MenuCompositionVO(List.of(new MenuCategoryInput("C","Category",0,List.of(item)))),
                List.of(new MenuContextInput("POS","DINE_IN",null,null)));
    }
    @Test void replaceEmptyDoesNotInheritMutableGroupsAndFreezesExactDishMetadata() {
        var working=mock(MenuWorkingDefinitionAccess.class); var dishes=mock(DishMapper.class);
        var links=mock(DishOptionGroupMapper.class); var json=new ObjectMapper();
        when(working.capture("E","A","M",true)).thenReturn(definition("REPLACE"));
        var dish=new FnbDish(); dish.setEntityNo("E"); dish.setGoodsNo("G"); dish.setStatus("ACTIVE");
        dish.setDishKind("SINGLE"); dish.setPrepSeconds(15); dish.setConfigVersion(2L); dish.setVersion(3L);
        dish.setPresentation(json.writeValueAsString(new
                DishPresentation("Frozen",List.of(),List.of(),List.of(),List.of())));
        dish.setMeasurementPolicy(json.writeValueAsString(new MeasurementPolicy("EA","COUNT",0,null)));
        when(dishes.selectOne(any())).thenReturn(dish);
        var inherited=new FnbDishOptionGroup(); inherited.setGroupNo("MUTABLE");
        when(links.selectList(any())).thenReturn(List.of(inherited));
        var source=new MenuPublicationSource(working,dishes,links,mock(OptionGroupMapper.class),
                mock(BundleMapper.class),mock(RecipeMapper.class),mock(MenuChannelBindingMapper.class),json);
        var captured=source.capture("E","A","M",true,(kind,no) -> {
            throw new AssertionError("REPLACE empty must not select inherited group");
        });
        assertThat(captured.itemOptions().getFirst().optionGroupNos()).isEmpty();
        assertThat(captured.dishes().getFirst().presentation().displayName()).isEqualTo("Frozen");
        dish.setPresentation("{}");
        assertThat(captured.dishes().getFirst().presentation().displayName()).isEqualTo("Frozen");
        assertThat(source.goodsNos(captured)).containsExactly("G");
        assertThat(source.skuNos(captured)).containsExactly("S");
    }
    @Test void disabledDishAndMissingInheritedPublicationFailClosed() {
        var working=mock(MenuWorkingDefinitionAccess.class); var dishes=mock(DishMapper.class);
        var links=mock(DishOptionGroupMapper.class); var json=new ObjectMapper();
        when(working.capture("E","A","M",false)).thenReturn(definition("INHERIT"));
        var dish=new FnbDish(); dish.setEntityNo("E"); dish.setGoodsNo("G"); dish.setStatus("DISABLED");
        when(dishes.selectOne(any())).thenReturn(dish);
        var source=new MenuPublicationSource(working,dishes,links,mock(OptionGroupMapper.class),
                mock(BundleMapper.class),mock(RecipeMapper.class),mock(MenuChannelBindingMapper.class),json);
        assertThatThrownBy(() -> source.capture("E","A","M",false,(kind,no) -> null))
                .isInstanceOf(BizException.class);
        dish.setStatus("DRAFT"); dish.setDishKind("SINGLE"); dish.setPrepSeconds(0);
        dish.setConfigVersion(0L); dish.setVersion(0L);
        dish.setPresentation(json.writeValueAsString(new
                DishPresentation(null,List.of(),List.of(),List.of(),List.of())));
        dish.setMeasurementPolicy(json.writeValueAsString(new MeasurementPolicy("EA","COUNT",0,null)));
        var inherited=new FnbDishOptionGroup(); inherited.setEntityNo("E"); inherited.setGoodsNo("G");
        inherited.setGroupNo("GROUP"); inherited.setSortNo(0);
        when(links.selectList(any())).thenReturn(List.of(inherited));
        assertThatThrownBy(() -> source.capture("E","A","M",false,(kind,no) -> null))
                .isInstanceOf(BizException.class);
    }
    record DishFixture(MenuPublicationSource source,FnbDish dish,ObjectMapper json) { }
    private DishFixture dishFixture() {
        var working=mock(MenuWorkingDefinitionAccess.class); var dishes=mock(DishMapper.class);
        var links=mock(DishOptionGroupMapper.class); var json=new ObjectMapper();
        when(working.capture("E","A","M",false)).thenReturn(definition("REPLACE"));
        var dish=new FnbDish(); dish.setEntityNo("E"); dish.setGoodsNo("G"); dish.setStatus("DRAFT");
        dish.setDishKind("SINGLE"); dish.setPrepSeconds(0); dish.setConfigVersion(0L); dish.setVersion(0L);
        dish.setPresentation(json.writeValueAsString(new
                DishPresentation(null,List.of(),List.of(),List.of(),List.of())));
        dish.setMeasurementPolicy(json.writeValueAsString(new MeasurementPolicy("EA","COUNT",0,null)));
        when(dishes.selectOne(any())).thenReturn(dish);
        return new DishFixture(new MenuPublicationSource(working,dishes,links,mock(OptionGroupMapper.class),
                mock(BundleMapper.class),mock(RecipeMapper.class),mock(MenuChannelBindingMapper.class),json),dish,json);
    }
    @Test void nullDishStatusCannotBecomeFrozenMenuMetadata() {
        var f=dishFixture(); f.dish().setStatus(null);
        assertThatThrownBy(() -> f.source().capture("E","A","M",false,(kind,no) -> null))
                .isInstanceOf(BizException.class);
    }
    @Test void unknownDishStatusCannotBecomeFrozenMenuMetadata() {
        var f=dishFixture(); f.dish().setStatus("UNKNOWN");
        assertThatThrownBy(() -> f.source().capture("E","A","M",false,(kind,no) -> null))
                .isInstanceOf(BizException.class);
    }
    @Test void unknownDishKindCannotBecomeFrozenMenuMetadata() {
        var f=dishFixture(); f.dish().setDishKind("UNKNOWN");
        assertThatThrownBy(() -> f.source().capture("E","A","M",false,(kind,no) -> null))
                .isInstanceOf(BizException.class);
    }
    @Test void unsupportedMeasurementCannotBecomeFrozenMenuMetadata() {
        var f=dishFixture();
        f.dish().setMeasurementPolicy(f.json().writeValueAsString(new MeasurementPolicy("KG","WEIGHT",3,null)));
        assertThatThrownBy(() -> f.source().capture("E","A","M",false,(kind,no) -> null))
                .isInstanceOf(BizException.class);
    }
    @Test void inheritedGroupRetainsExactFrozenModifierRevisionAndEmbeddedSnapshot() {
        var working=mock(MenuWorkingDefinitionAccess.class); var dishes=mock(DishMapper.class);
        var links=mock(DishOptionGroupMapper.class); var json=new ObjectMapper();
        when(working.capture("E","A","M",true)).thenReturn(definition("INHERIT"));
        var dish=new FnbDish(); dish.setEntityNo("E"); dish.setGoodsNo("G"); dish.setStatus("DRAFT");
        dish.setDishKind("SINGLE"); dish.setPrepSeconds(0); dish.setConfigVersion(0L); dish.setVersion(0L);
        dish.setPresentation(json.writeValueAsString(new
                DishPresentation(null,List.of(),List.of(),List.of(),List.of())));
        dish.setMeasurementPolicy(json.writeValueAsString(new MeasurementPolicy("EA","COUNT",0,null)));
        when(dishes.selectOne(any())).thenReturn(dish);
        var link=new FnbDishOptionGroup(); link.setEntityNo("E"); link.setGoodsNo("G"); link.setGroupNo("GROUP");
        link.setSortNo(0); when(links.selectList(any())).thenReturn(List.of(link));
        var modifier=new PublicationSnapshot(1,"MODIFIER","MOD",0,new ModifierConfigVO("MOD","ADDON",0,"DRAFT",
                new ModifierConfigVO.Data("Original",null,null,List.of())),null,List.of(),List.of(),List.of(),null);
        var old=revision(json,"OLD","MODIFIER","MOD",PublicationDocuments.freeze(json,modifier));
        var group=new PublicationSnapshot(1,"OPTION_GROUP","GROUP",0,null,
                new OptionGroupConfigVO("GROUP","OPTION_GROUP",0,"DRAFT",new OptionGroupConfigVO.Data("Group",
                        BigDecimal.ZERO,BigDecimal.ONE,null,Map.of(),List.of())),
                List.of(new PublicationSnapshot.Dependency("OLD","MODIFIER","MOD",1,old.getContentHash())),
                List.of(),List.of(),null);
        var groupRow=revision(json,"GROUP-REV","OPTION_GROUP","GROUP",PublicationDocuments.freeze(json,group));
        var source=new MenuPublicationSource(working,dishes,links,mock(OptionGroupMapper.class),
                mock(BundleMapper.class),mock(RecipeMapper.class),mock(MenuChannelBindingMapper.class),json);
        var captured=source.capture("E","A","M",true,(kind,no) -> {
            assertThat(kind).isEqualTo("OPTION_GROUP"); return groupRow;
        },revision -> { assertThat(revision).isEqualTo("OLD"); return old; },List.of());
        assertThat(captured.dependencies()).extracting(d -> d.revisionNo()).containsExactly("OLD","GROUP-REV");
        String original=captured.dependencies().getFirst().definitionSnapshot();
        old.setDefinitionSnapshot("{}");
        assertThat(captured.dependencies().getFirst().definitionSnapshot()).isEqualTo(original);
    }
    static FnbConfigRevision revision(ObjectMapper json,String revision,String kind,String number,String document) {
        var row=new FnbConfigRevision(); row.setEntityNo("E"); row.setRevisionNo(revision); row.setConfigKind(kind);
        row.setConfigNo(number); row.setRevision(1L); row.setContentSchema(1); row.setStatus("EFFECTIVE");
        row.setScopeStoreNos("[\"A\"]"); row.setDefinitionSnapshot(document);
        row.setContentHash(PublicationDocuments.hash(json,document)); return row;
    }

}

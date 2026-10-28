package ai.neargo.shop.food.policy;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.DishPresentation;
import ai.neargo.shop.food.dto.DishVO;
import ai.neargo.shop.food.dto.MeasurementPolicy;
import ai.neargo.shop.food.dto.MenuChannelBindingVO;
import ai.neargo.shop.food.dto.MenuItemInput;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot.Captured;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot.Dependency;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot.ItemOptions;
import ai.neargo.shop.food.dto.PublicationSnapshot;
import ai.neargo.shop.food.dto.RecipePublicationSnapshot;
import ai.neargo.shop.food.entity.FnbConfigRevision;
import ai.neargo.shop.food.entity.FnbDish;
import ai.neargo.shop.food.entity.FnbDishOptionGroup;
import ai.neargo.shop.food.entity.FnbMenuChannelBinding;
import ai.neargo.shop.food.mapper.FoodMappers.BundleMapper;
import ai.neargo.shop.food.mapper.FoodMappers.DishMapper;
import ai.neargo.shop.food.mapper.FoodMappers.DishOptionGroupMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuChannelBindingMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OptionGroupMapper;
import ai.neargo.shop.food.mapper.FoodMappers.RecipeMapper;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.GoodsFact;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.Function;
import tools.jackson.databind.ObjectMapper;

/** Captures local definitions and immutable dependencies; the caller obtains original Goods authority separately. */
public final class MenuPublicationSource {
    private final MenuWorkingDefinitionAccess working;
    private final DishMapper dishes;
    private final DishOptionGroupMapper dishGroups;
    private final MenuChannelBindingMapper bindings;
    private final ObjectMapper json;
    public MenuPublicationSource(MenuWorkingDefinitionAccess working,DishMapper dishes,DishOptionGroupMapper dishGroups,
            OptionGroupMapper optionGroups,BundleMapper bundles,RecipeMapper recipes,
            MenuChannelBindingMapper bindings,ObjectMapper json) {
        this.working=working; this.dishes=dishes; this.dishGroups=dishGroups; this.bindings=bindings; this.json=json;
    }
    /** Preliminary capture can omit ALL bundle dependencies until original full Goods identity has been captured. */
    public Captured capture(String entity,String store,String number,boolean lock,
            BiFunction<String,String,FnbConfigRevision> selected) {
        return capture(entity,store,number,lock,selected,null,null);
    }
    /** Exact revision lookup follows the already-frozen dependency graph rather than upgrading child references. */
    public Captured capture(String entity,String store,String number,boolean lock,
            BiFunction<String,String,FnbConfigRevision> selected,Function<String,FnbConfigRevision> byRevision,
            List<GoodsFact> goodsFacts) {
        return DataScopeContext.executeWithoutScope(() -> captureScoped(entity,store,number,lock,selected,
                byRevision,goodsFacts));
    }
    private Captured captureScoped(String entity,String store,String number,boolean lock,
            BiFunction<String,String,FnbConfigRevision> selected,Function<String,FnbConfigRevision> byRevision,
            List<GoodsFact> goodsFacts) {
        MenuDefinitionPolicy.key(entity); MenuDefinitionPolicy.key(store); MenuDefinitionPolicy.key(number);
        var definition=working.capture(entity,store,number,lock);
        if(definition==null || !MenuDefinitionPolicy.valid(definition.config(),store)
                || !number.equals(definition.config().menuNo()) || "DISABLED".equals(definition.config().status())
                || definition.schedule()==null || definition.contexts()==null || definition.contexts().isEmpty()) bad();
        MenuCompositionDefinitions.normalizeStructure(definition.composition());
        MenuContextDefinitions.normalize(definition.contexts());
        var items=definition.composition().categories().stream().flatMap(c -> c.items().stream())
                .filter(i -> Boolean.TRUE.equals(i.enabled())).toList();
        if(items.isEmpty()) bad();
        var frozenDishes=new ArrayList<DishVO>(); var metadata=new TreeMap<String,DishVO>();
        var options=new ArrayList<ItemOptions>(); var dependencies=new TreeMap<String,Dependency>();
        var selectedBindings=new TreeMap<String,MenuChannelBindingVO>();
        var goods=new HashMap<String,GoodsFact>();
        if(goodsFacts!=null) for(var g:goodsFacts) {
            if(g==null || !entity.equals(g.entityNo()) || !store.equals(g.storeNo())
                    || goods.put(g.goodsNo(),g)!=null) bad();
        }
        for(String goodsNo:items.stream().map(MenuItemInput::goodsNo).distinct().sorted().toList()) {
            var query=Wrappers.<FnbDish>lambdaQuery().eq(FnbDish::getEntityNo,entity).eq(FnbDish::getGoodsNo,goodsNo);
            if(lock) query.last("FOR UPDATE"); var row=dishes.selectOne(query);
            if(row==null || !entity.equals(row.getEntityNo()) || !goodsNo.equals(row.getGoodsNo())
                    || !("DRAFT".equals(row.getStatus()) || "ACTIVE".equals(row.getStatus()))
                    || row.getVersion()==null || row.getVersion()<0
                    || row.getConfigVersion()==null || row.getConfigVersion()<0 || row.getPrepSeconds()==null) bad();
            var view=new DishVO(goodsNo,row.getDishKind(),row.getPrepSeconds(),
                    json.readValue(row.getPresentation(),DishPresentation.class),
                    json.readValue(row.getMeasurementPolicy(),MeasurementPolicy.class),
                    row.getConfigVersion(),row.getVersion());
            frozenDishes.add(view); metadata.put(goodsNo,view);
        }
        for(var item:items) {
            List<String> groups=item.optionGroupNos();
            if("INHERIT".equals(item.optionMode())) {
                var q=Wrappers.<FnbDishOptionGroup>lambdaQuery().eq(FnbDishOptionGroup::getEntityNo,entity)
                        .eq(FnbDishOptionGroup::getGoodsNo,item.goodsNo())
                        .orderByAsc(FnbDishOptionGroup::getSortNo,FnbDishOptionGroup::getGroupNo);
                if(lock) q.last("FOR UPDATE");
                var rows=dishGroups.selectList(q);
                if(rows.stream().anyMatch(r -> !entity.equals(r.getEntityNo())
                        || !item.goodsNo().equals(r.getGoodsNo()))) bad();
                groups=rows.stream().map(FnbDishOptionGroup::getGroupNo).toList();
            }
            if(groups.size()>50 || new HashSet<>(groups).size()!=groups.size()) bad();
            options.add(new ItemOptions(item.menuItemNo(),groups));
            for(String group:groups) add(entity,store,"OPTION_GROUP",group,selected.apply("OPTION_GROUP",group),
                    selected,byRevision,dependencies,new HashSet<>());
            for(var recipe:item.skuRecipes()) {
                var revision=selected.apply("RECIPE",recipe.recipeNo());
                add(entity,store,"RECIPE",recipe.recipeNo(),revision,selected,byRevision,dependencies,new HashSet<>());
                var frozen=json.readValue(revision.getDefinitionSnapshot(),RecipePublicationSnapshot.class).recipe();
                if(!store.equals(frozen.storeNo()) || !recipe.skuNo().equals(frozen.skuNo())) bad();
            }
            if("BUNDLE".equals(metadata.get(item.goodsNo()).dishKind())) {
                List<String> skuNos=item.skuNos();
                if("ALL".equals(item.skuScope())) {
                    if(goodsFacts==null) continue;
                    var fact=goods.get(item.goodsNo()); if(fact==null) bad();
                    skuNos=fact.skus().stream().map(s -> s.skuNo()).toList();
                }
                for(String sku:skuNos) add(entity,store,"BUNDLE",sku,selected.apply("BUNDLE",sku),
                        selected,byRevision,dependencies,new HashSet<>());
            }
        }
        for(var context:definition.contexts()) if(context.channelBindingNo()!=null) {
            var q=Wrappers.<FnbMenuChannelBinding>lambdaQuery().eq(FnbMenuChannelBinding::getEntityNo,entity)
                    .eq(FnbMenuChannelBinding::getStoreNo,store)
                    .eq(FnbMenuChannelBinding::getBindingNo,context.channelBindingNo());
            if(lock) q.last("FOR UPDATE"); var row=bindings.selectOne(q);
            if(row==null || !entity.equals(row.getEntityNo()) || !store.equals(row.getStoreNo())
                    || !number.equals(row.getMenuNo()) || !"ACTIVE".equals(row.getStatus())) bad();
            selectedBindings.put(row.getBindingNo(),new MenuChannelBindingVO(row.getBindingNo(),row.getMenuNo(),store,
                    row.getProvider(),row.getChannelAccountRef(),row.getExternalStoreRef(),
                    row.getStatus(),row.getVersion()));
        }
        options.sort(Comparator.comparing(ItemOptions::menuItemNo));
        var captured=new Captured(entity,store,definition,frozenDishes,options,List.copyOf(dependencies.values()),
                List.copyOf(selectedBindings.values()));
        try { MenuPublicationDocuments.validateCapturedStructure(captured); }
        catch(RuntimeException invalid) { bad(); }
        return captured;
    }
    private void add(String entity,String store,String kind,String number,FnbConfigRevision row,
            BiFunction<String,String,FnbConfigRevision> selected,Function<String,FnbConfigRevision> byRevision,
            Map<String,Dependency> result,HashSet<String> path) {
        MenuDefinitionPolicy.key(number);
        if(row==null || !entity.equals(row.getEntityNo()) || !kind.equals(row.getConfigKind())
                || !number.equals(row.getConfigNo()) || row.getRevisionNo()==null || row.getRevision()==null
                || row.getRevision()<1 || row.getContentSchema()==null || "CANCELLED".equals(row.getStatus())) bad();
        var scope=json.readValue(row.getScopeStoreNos(),String[].class);
        if(scope==null || !List.of(scope).contains(store)) bad();
        int schema=PublicationDocuments.validate(json,kind,number,row.getDefinitionSnapshot());
        if(schema!=row.getContentSchema()
                || !PublicationDocuments.hash(json,row.getDefinitionSnapshot()).equals(row.getContentHash())) bad();
        var dep=new Dependency(row.getRevisionNo(),kind,number,row.getRevision(),schema,row.getContentHash(),
                row.getDefinitionSnapshot());
        String key=kind+":"+number; var existing=result.get(key);
        if(existing!=null) { if(!existing.equals(dep)) bad(); return; }
        if(result.size()>=1000 || !path.add(key)) bad();
        result.put(key,dep);
        if((schema==1 || schema==6) && Set.of("MODIFIER", "OPTION_GROUP").contains(kind)) {
            var frozen=json.readValue(row.getDefinitionSnapshot(),PublicationSnapshot.class);
            for(var ref:frozen.dependencies()) {
                var child=byRevision==null?selected.apply(ref.configKind(),ref.configNo())
                        :byRevision.apply(ref.revisionNo());
                if(child==null || !ref.revisionNo().equals(child.getRevisionNo()) || ref.revision()!=child.getRevision()
                        || !ref.contentHash().equals(child.getContentHash())) bad();
                add(entity,store,ref.configKind(),ref.configNo(),child,selected,byRevision,result,path);
            }
        }
        path.remove(key);
    }
    public List<String> goodsNos(Captured captured) { return List.copyOf(MenuPublicationDocuments.goodsNos(captured)); }
    public List<String> skuNos(Captured captured) {
        var result=new TreeSet<String>();
        MenuPublicationDocuments.items(captured).forEach(i -> result.addAll(i.skuNos()));
        result.addAll(MenuPublicationDocuments.dependencySkus(json,captured)); return List.copyOf(result);
    }
    public long currentVersion(String entity, String store, String number, boolean lock) {
        return working.currentVersion(entity, store, number, lock);
    }
    public void advance(String entity,String store,String number,long version,FnbConfigRevision revision) {
        working.advance(entity,store,number,version,revision);
    }
    private static void bad() { throw BizException.of(ErrorCode.BAD_REQUEST); }
}

package ai.neargo.shop.food.mapper;
import ai.neargo.shop.common.config.IndustryTableMapper;
import ai.neargo.shop.common.config.TradeTableMapper;
import ai.neargo.shop.food.dto.ProductionContracts.ListQuery;
import ai.neargo.shop.food.entity.FnbProductionConsumptionLine;
import ai.neargo.shop.food.entity.FnbProductionConsumption;
import ai.neargo.shop.food.entity.FnbBusinessDay;
import ai.neargo.shop.food.entity.FnbCloseBalance;
import ai.neargo.shop.food.entity.FnbCloseRun;
import ai.neargo.shop.food.entity.FnbCloseException;
import ai.neargo.shop.food.entity.FnbCloseAdjustment;
import ai.neargo.shop.food.entity.FnbKitchenEvent;
import ai.neargo.shop.food.entity.FnbKitchenTicket;
import ai.neargo.shop.food.entity.FnbKitchenTask;
import ai.neargo.shop.food.entity.FnbCourse;

import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbQueueTicket;
import ai.neargo.shop.food.entity.FnbSettlement;
import ai.neargo.shop.food.entity.FnbSettlementItem;
import ai.neargo.shop.food.entity.FnbChargeIntent;
import ai.neargo.shop.food.entity.FnbCoreOrderBinding;
import ai.neargo.shop.food.entity.FnbOrderLine;
import ai.neargo.shop.food.entity.FnbCoreLineAllocation;
import ai.neargo.shop.food.entity.FnbOrderComponent;
import ai.neargo.shop.food.entity.FnbOrderModifier;
import ai.neargo.shop.food.entity.FnbStation;
import ai.neargo.shop.food.entity.FnbStockBalance;
import ai.neargo.shop.food.entity.FnbStockSyncDelivery;
import java.time.LocalDateTime;
import java.util.Collection;
import ai.neargo.shop.food.entity.FnbCheckCart;
import ai.neargo.shop.food.entity.FnbCheckCartItem;
import ai.neargo.shop.food.entity.FnbSkuStation;
import ai.neargo.shop.food.entity.FnbSalesRestriction;
import ai.neargo.shop.food.entity.FnbSalesQuotaClaim;
import ai.neargo.shop.food.entity.FnbReturn;
import ai.neargo.shop.food.entity.FnbReturnItem;
import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.entity.FnbMenu;
import ai.neargo.shop.food.entity.FnbMenuItemOptionGroup;
import ai.neargo.shop.food.entity.FnbMenuItemSku;
import ai.neargo.shop.food.entity.FnbMenuItem;
import ai.neargo.shop.food.entity.FnbMenuContext;
import ai.neargo.shop.food.entity.FnbMenuChannelBinding;
import ai.neargo.shop.food.entity.FnbMenuCategory;
import ai.neargo.shop.food.entity.FnbMenuSchedule;
import ai.neargo.shop.food.entity.FnbMenuException;
import ai.neargo.shop.food.entity.FnbMenuStore;
import ai.neargo.shop.food.entity.FnbBundle;
import ai.neargo.shop.food.entity.FnbBundleChoice;
import ai.neargo.shop.food.entity.FnbBundleGroup;
import ai.neargo.shop.food.entity.FnbCheck;
import ai.neargo.shop.food.entity.FnbConfigRevision;
import ai.neargo.shop.food.entity.FnbDish;
import ai.neargo.shop.food.entity.FnbDishOptionGroup;
import ai.neargo.shop.food.entity.FnbGuestSession;
import ai.neargo.shop.food.entity.FnbModifier;
import ai.neargo.shop.food.entity.FnbModifierComponent;
import ai.neargo.shop.food.entity.FnbOptionGroup;
import ai.neargo.shop.food.entity.FnbOptionGroupItem;
import ai.neargo.shop.food.entity.FnbRecipe;
import ai.neargo.shop.food.entity.FnbRecipeAdjustment;
import ai.neargo.shop.food.entity.FnbRecipeLine;
import ai.neargo.shop.food.entity.FnbReservation;
import ai.neargo.shop.food.entity.FnbSeat;
import ai.neargo.shop.food.entity.FnbServiceCalendar;
import ai.neargo.shop.food.entity.FnbStorePolicy;
import ai.neargo.shop.food.entity.FnbTable;
import ai.neargo.shop.food.entity.FnbTableArea;
import ai.neargo.shop.food.entity.FnbTableOccupancy;
import ai.neargo.shop.food.entity.FnbTableOperation;
import ai.neargo.shop.food.entity.FnbTableToken;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 按实际落地的持久化资源增加嵌套 Mapper，不预生成全域空壳。 */
public final class FoodMappers {
    private FoodMappers() {
    }

    public interface CheckCartMapper extends TradeTableMapper<FnbCheckCart> {
        @Select("SELECT COUNT(*) FROM fnb_order_batch WHERE tenant_no='MAIN' AND entity_no=#{entity} "
                + "AND cart_no=#{cart} AND deleted=0 AND status IN "
                + "('PREPARED','PLACING','UNKNOWN','BOUND','ACCEPTED','" + FnbReturn.CANCEL_PENDING + "')")
        long activePlacementCount(@Param("entity") String entity, @Param("cart") String cart);
        @Select("SELECT COUNT(*) FROM fnb_order_batch b JOIN fnb_core_order_binding o "
                + "ON o.tenant_no=b.tenant_no AND o.entity_no=b.entity_no AND o.batch_no=b.batch_no "
                + "WHERE b.tenant_no='MAIN' AND b.entity_no=#{entity} AND b.store_no=#{store} "
                + "AND b.cart_no=#{cart} AND b.buyer_no=#{buyer} AND b.deleted=0 AND o.deleted=0 "
                + "AND b.status IN ('BOUND','ACCEPTED') AND o.status='BOUND'")
        long submittedBuyerBindingCount(@Param("entity") String entity, @Param("store") String store,
                @Param("cart") String cart, @Param("buyer") String buyer);
    }

    public interface SettlementMapper extends TradeTableMapper<FnbSettlement> {
        @Select("SELECT COUNT(*) FROM fnb_return WHERE tenant_no='MAIN' AND entity_no=#{entity} "
                + "AND batch_no=#{batch} AND deleted=0 AND status NOT IN ('REJECTED','CANCELLED') "
                + "AND (status<>'CLOSED' OR financial_status IS NULL OR financial_status<>'NONE')")
        long localReturnCount(@Param("entity") String entity, @Param("batch") String batch);
    }
    public interface SettlementItemMapper extends TradeTableMapper<FnbSettlementItem> {
        @Select("SELECT COUNT(*) FROM fnb_settlement_item i JOIN fnb_settlement s ON s.tenant_no=i.tenant_no "
                + "AND s.entity_no=i.entity_no AND s.settlement_no=i.settlement_no WHERE i.tenant_no='MAIN' "
                + "AND i.entity_no=#{entity} AND s.store_no=#{store} AND i.payment_root_no=#{root} "
                + "AND i.scope_key=#{scope} AND i.deleted=0 AND s.deleted=0 "
                + "AND s.status IN ('FROZEN','COLLECTING','SETTLED')")
        long activeFullScopeCount(@Param("entity") String entity, @Param("store") String store,
                @Param("root") String root, @Param("scope") String scope);
    }
    public interface ChargeIntentMapper extends TradeTableMapper<FnbChargeIntent> { }

    public interface OrderBatchMapper extends TradeTableMapper<FnbOrderBatch> {
        /** 批次所在门店与状态：出品在行业库，按批次号回交易库取门店与接单状态（不联表）。 */
        record BatchState(String batchNo, String storeNo, String status, LocalDateTime acceptedAt) {}

        @Select("<script>SELECT batch_no,store_no,status,accepted_at FROM fnb_order_batch "
                + "WHERE tenant_no='MAIN' AND entity_no=#{entity} AND deleted=0 AND batch_no IN "
                + "<foreach collection='batches' item='b' open='(' separator=',' close=')'>#{b}</foreach>"
                + "</script>")
        List<BatchState> states(@Param("entity") String entity, @Param("batches") Collection<String> batches);
    }
    public interface CoreOrderBindingMapper extends TradeTableMapper<FnbCoreOrderBinding> { }
    public interface OrderLineMapper extends TradeTableMapper<FnbOrderLine> {
        /** 某门店某 SKU 的点菜行：出品耗料按商品筛选时先在交易库取行号，再到行业库取事件。 */
        @Select("SELECT l.food_line_no FROM fnb_order_line l JOIN fnb_order_batch b ON b.tenant_no=l.tenant_no "
                + "AND b.entity_no=l.entity_no AND b.batch_no=l.batch_no AND b.deleted=0 "
                + "WHERE l.tenant_no='MAIN' AND l.entity_no=#{entity} AND b.store_no=#{store} "
                + "AND l.sku_no=#{sku} AND l.deleted=0")
        List<String> lineNosBySku(@Param("entity") String entity, @Param("store") String store,
                @Param("sku") String sku);
    }
    public interface CoreLineAllocationMapper extends TradeTableMapper<FnbCoreLineAllocation> { }
    public interface OrderComponentMapper extends TradeTableMapper<FnbOrderComponent> { }
    public interface OrderModifierMapper extends TradeTableMapper<FnbOrderModifier> { }

    public interface CheckCartItemMapper extends TradeTableMapper<FnbCheckCartItem> {
        @Select("SELECT * FROM fnb_check_cart_item WHERE entity_no=#{entity} AND cart_no=#{cart} "
                + "AND source_line_ref=#{source} FOR UPDATE")
        FnbCheckCartItem history(@Param("entity") String entity, @Param("cart") String cart,
                @Param("source") String source);
        @Update("UPDATE fnb_check_cart_item SET qty=#{qty},seat_no=#{seat},course_no=#{course},"
                + "selection=#{selection},deleted=#{deleted},version=version+1,updated_by=#{actor},"
                + "updated_at=#{at} WHERE entity_no=#{entity} AND cart_no=#{cart} AND source_line_ref=#{source}")
        int retain(@Param("entity") String entity, @Param("cart") String cart, @Param("source") String source,
                @Param("qty") BigDecimal qty, @Param("seat") String seat, @Param("course") String course,
                @Param("selection") String selection, @Param("deleted") int deleted,
                @Param("actor") String actor, @Param("at") LocalDateTime at);
    }

    public interface RecipeAdjustmentMapper extends BaseMapper<FnbRecipeAdjustment> {
        @Delete("DELETE FROM fnb_recipe_adjustment WHERE entity_no=#{entity} AND recipe_no=#{recipe}")
        int replaceDraftRows(@Param("entity") String entity, @Param("recipe") String recipe);
    }

    public interface MenuCategoryMapper extends TradeTableMapper<FnbMenuCategory> {
        @Delete("DELETE FROM fnb_menu_category WHERE entity_no=#{entity} AND menu_no=#{menu}")
        int replaceDraftRows(@Param("entity") String entity, @Param("menu") String menu);
    }

    public interface MenuItemMapper extends TradeTableMapper<FnbMenuItem> {
        @Delete("DELETE FROM fnb_menu_item WHERE entity_no=#{entity} AND category_no IN (SELECT categor"
                + "y_no FROM fnb_menu_category WHERE entity_no=#{entity} AND menu_no=#{menu})")
        int replaceDraftRows(@Param("entity") String entity, @Param("menu") String menu);
    }

    public interface MenuItemSkuMapper extends TradeTableMapper<FnbMenuItemSku> {
        @Delete("DELETE FROM fnb_menu_item_sku WHERE entity_no=#{entity} AND menu_item_no IN (SELECT me"
                + "nu_item_no FROM fnb_menu_item WHERE entity_no=#{entity} AND category_no IN (SELECT cat"
                + "egory_no FROM fnb_menu_category WHERE entity_no=#{entity} AND menu_no=#{menu}))")
        int replaceDraftRows(@Param("entity") String entity, @Param("menu") String menu);

        @Select("SELECT COUNT(*) FROM fnb_recipe WHERE entity_no=#{entity} AND store_no=#{store} "
                + "AND recipe_no=#{recipe} AND sku_no=#{sku} AND deleted=0 AND status IN ('DRAFT','ACTIVE')")
        long recipeForSku(@Param("entity") String entity, @Param("store") String store,
                @Param("recipe") String recipe, @Param("sku") String sku);
    }

    public interface MenuItemOptionGroupMapper extends TradeTableMapper<FnbMenuItemOptionGroup> {
        @Delete("DELETE FROM fnb_menu_item_option_group WHERE entity_no=#{entity} AND menu_item_no IN ("
                + "SELECT menu_item_no FROM fnb_menu_item WHERE entity_no=#{entity} AND category_no IN (S"
                + "ELECT category_no FROM fnb_menu_category WHERE entity_no=#{entity} AND menu_no=#{menu}"
                + "))")
        int replaceDraftRows(@Param("entity") String entity, @Param("menu") String menu);

        @Select("SELECT COUNT(*) FROM fnb_option_group WHERE entity_no=#{entity} AND group_no=#{group} "
                + "AND deleted=0 AND status IN ('DRAFT','ACTIVE')")
        long availableGroup(@Param("entity") String entity, @Param("group") String group);
    }

    public interface MenuScheduleMapper extends TradeTableMapper<FnbMenuSchedule> {
        @Delete("DELETE FROM fnb_menu_schedule WHERE entity_no=#{entity} AND menu_no=#{menu}")
        int replaceDraftRows(@Param("entity") String entity, @Param("menu") String menu);
    }
    public interface MenuExceptionMapper extends TradeTableMapper<FnbMenuException> {
        @Delete("DELETE FROM fnb_menu_exception WHERE entity_no=#{entity} AND menu_no=#{menu}")
        int replaceDraftRows(@Param("entity") String entity, @Param("menu") String menu);
    }

    public interface MenuMapper extends TradeTableMapper<FnbMenu> { }
    public interface MenuStoreMapper extends TradeTableMapper<FnbMenuStore> {
        /** 门店上架的、未停用的菜单号：菜单在交易库，菜单发布修订在行业库，两边各查一次（不联表）。 */
        @Select("SELECT DISTINCT ms.menu_no FROM fnb_menu_store ms JOIN fnb_menu m ON m.entity_no=ms.entity_no "
                + "AND m.menu_no=ms.menu_no AND m.deleted=0 AND m.status!='DISABLED' "
                + "WHERE ms.entity_no=#{entity} AND ms.store_no=#{store} AND ms.deleted=0")
        List<String> activeMenuNos(@Param("entity") String entity, @Param("store") String store);
    }

    public interface RecipeMapper extends BaseMapper<FnbRecipe> { }
    public interface RecipeLineMapper extends BaseMapper<FnbRecipeLine> {
        @Delete("DELETE FROM fnb_recipe_line WHERE entity_no=#{entity} AND recipe_no=#{recipe}")
        int replaceDraftRows(@Param("entity") String entity, @Param("recipe") String recipe);
    }

    public interface BundleMapper extends TradeTableMapper<FnbBundle> { }
    public interface BundleGroupMapper extends TradeTableMapper<FnbBundleGroup> {
        // Only the locked mutable definition is replaceable; never a frozen publication.
        @Delete("DELETE FROM fnb_bundle_group WHERE entity_no=#{entity} AND bundle_sku_no=#{sku}")
        int replaceDraftRows(@Param("entity") String entity, @Param("sku") String sku);
    }
    public interface BundleChoiceMapper extends TradeTableMapper<FnbBundleChoice> {
        @Delete("DELETE FROM fnb_bundle_choice WHERE entity_no=#{entity} AND group_no IN "
                + "(SELECT group_no FROM fnb_bundle_group WHERE entity_no=#{entity} AND bundle_sku_no=#{sku})")
        int replaceDraftRows(@Param("entity") String entity, @Param("sku") String sku);
    }

    public interface DishOptionGroupMapper extends TradeTableMapper<FnbDishOptionGroup> {
        // Only the authorized current-store working association is replaced, never another store's binding.
        @Delete("DELETE FROM fnb_dish_option_group WHERE entity_no=#{entity} "
                + "AND group_no=#{group} AND goods_no=#{goods}")
        int removeDraftBinding(@Param("entity") String entity, @Param("group") String group,
                @Param("goods") String goods);
    }
    public interface OptionGroupMapper extends TradeTableMapper<FnbOptionGroup> { }
    public interface OptionGroupItemMapper extends TradeTableMapper<FnbOptionGroupItem> {
        // Working rows are replaceable; execution/history must use complete immutable revision snapshots.
        @Delete("DELETE FROM fnb_option_group_item WHERE entity_no=#{entity} AND group_no=#{group}")
        int replaceDraftRows(@Param("entity") String entity, @Param("group") String group);
    }
    /**
     * No generic update/delete API is exposed for publication facts.
     *
     * <p>配置修订已并入行业配置修订 ind_config_revision（TDD §2.5）：revision_no→policy_version_no、
     * config_no→policy_no、revision→revision_index、content_schema→schema_version、content_hash→snapshot_hash。
     * 查询按旧列名取别名（{@link #COLUMNS}），实体与服务不变；写入显式填 tenant_no='MAIN'（唯一键不约束空值）。
     */
    public interface ConfigRevisionMapper {
        String COLUMNS = "r.id,r.tenant_no,r.entity_no,r.created_at,r.created_by,r.updated_at,r.updated_by,"
                + "r.version,r.deleted,r.policy_version_no AS revision_no,r.config_kind,r.policy_no AS config_no,"
                + "r.revision_index AS revision,r.schema_version AS content_schema,r.definition_snapshot,"
                + "r.snapshot_hash AS content_hash,r.effective_at,r.status,r.previous_revision_no,r.scope_store_nos";
        // Fetch only timeline metadata; materialize large immutable JSON only for intersecting effective windows.
        // 菜单号由交易库的 MenuStoreMapper#activeMenuNos 给出，这里只查行业库的发布修订。
        @Select("<script>SELECT r.policy_version_no AS revision_no,r.entity_no,r.config_kind,"
                + "r.policy_no AS config_no,r.revision_index AS revision,r.effective_at,"
                + "r.status,r.scope_store_nos FROM ind_config_revision r "
                + "WHERE r.tenant_no='MAIN' AND r.entity_no=#{entity} AND r.config_kind='MENU' AND r.deleted=0 "
                + "AND r.status!='CANCELLED' AND r.policy_no IN "
                + "<foreach collection='menus' item='m' open='(' separator=',' close=')'>#{m}</foreach> "
                + "ORDER BY r.policy_no,r.effective_at,r.revision_index</script>")
        List<FnbConfigRevision> menuTimeline(@Param("entity") String entity, @Param("menus") Collection<String> menus);

        @Select("SELECT " + COLUMNS + " FROM ind_config_revision r WHERE r.tenant_no='MAIN' "
                + "AND r.entity_no=#{entity} AND r.config_kind=#{kind} "
                + "AND r.policy_no=#{number} AND r.deleted=0 ORDER BY r.revision_index DESC")
        List<FnbConfigRevision> findObject(@Param("entity") String entity, @Param("kind") String kind,
                @Param("number") String number);
        @Select("SELECT " + COLUMNS + " FROM ind_config_revision r WHERE r.tenant_no='MAIN' "
                + "AND r.entity_no=#{entity} AND r.policy_version_no=#{number} AND r.deleted=0")
        FnbConfigRevision findRevision(@Param("entity") String entity, @Param("number") String number);
        @Insert("INSERT INTO ind_config_revision(tenant_no,entity_no,created_at,created_by,updated_at,updated_by,"
                + "policy_version_no,config_kind,policy_no,revision_index,schema_version,definition_snapshot,snapshot_hash,"
                + "effective_at,status,previous_revision_no,scope_store_nos) VALUES('MAIN',#{entityNo},CURRENT_TIMESTAMP(3),"
                + "#{createdBy},CURRENT_TIMESTAMP(3),#{createdBy},#{revisionNo},#{configKind},#{configNo},#{revision},"
                + "#{contentSchema},#{definitionSnapshot},#{contentHash},#{effectiveAt},#{status},"
                + "#{previousRevisionNo},#{scopeStoreNos})")
        int append(FnbConfigRevision row);
    }

    public interface ModifierMapper extends TradeTableMapper<FnbModifier> { }
    public interface ModifierComponentMapper extends TradeTableMapper<FnbModifierComponent> {
        // Replace only the editable composition, never a published revision or transaction fact.
        @Delete("DELETE FROM fnb_modifier_component WHERE entity_no=#{entity} AND combo_no=#{combo}")
        int replaceDraftRows(@Param("entity") String entity, @Param("combo") String combo);
    }

    public interface DishMapper extends TradeTableMapper<FnbDish> { }

    public interface TableTokenMapper extends BaseMapper<FnbTableToken> { }

    public interface GuestSessionMapper extends BaseMapper<FnbGuestSession> { }

    public interface CheckMapper extends TradeTableMapper<FnbCheck> {
        // Presence is intentionally fail-closed, including historical or anomalously deleted facts.
        @Select("SELECT (SELECT COUNT(*) FROM fnb_order_batch WHERE entity_no=#{entity} AND check_no=#{check})"
                +"+(SELECT COUNT(*) FROM fnb_check_batch_membership WHERE entity_no=#{entity} AND check_no=#{check})"
                +"+(SELECT COUNT(*) FROM fnb_settlement_item WHERE entity_no=#{entity} AND check_no=#{check})"
                +"+(SELECT COUNT(*) FROM fnb_return WHERE entity_no=#{entity} AND check_no=#{check})"
                +"+(SELECT COUNT(*) FROM fnb_check_cart WHERE entity_no=#{entity} AND check_no=#{check} AND (status IS NULL OR status<>'CLOSED'))")
        long tradeFacts(@Param("entity") String entity,@Param("check") String check);
        /** 账单下的到店单号：堂食权益按到店单挂在行业库的人头权益上。 */
        @Select("SELECT form_no FROM fnb_service_form WHERE entity_no=#{entity} AND check_no=#{check}")
        List<String> formNos(@Param("entity") String entity,@Param("check") String check);
    }
    public interface TableOccupancyMapper extends IndustryTableMapper<FnbTableOccupancy> {
        @Select("SELECT * FROM fnb_table_occupancy WHERE entity_no=#{entity} AND table_no=#{table} AND ended_at IS NULL ORDER BY occupancy_no")
        List<FnbTableOccupancy> activeForTable(@Param("entity") String entity,@Param("table") String table);
        @Select("SELECT * FROM fnb_table_occupancy WHERE entity_no=#{entity} AND check_no=#{check} AND ended_at IS NULL ORDER BY occupancy_no")
        List<FnbTableOccupancy> activeForCheck(@Param("entity") String entity,@Param("check") String check);
    }
    public interface SeatMapper extends TradeTableMapper<FnbSeat> {
        @Select("SELECT * FROM fnb_seat WHERE entity_no=#{entity} AND check_no=#{check} ORDER BY seat_no")
        List<FnbSeat> history(@Param("entity") String entity,@Param("check") String check);
        @Update("UPDATE fnb_seat SET label=#{label},guest_ref=NULL,deleted=#{deleted},version=version+1,"
                +"updated_by=#{actor},updated_at=#{at} WHERE entity_no=#{entity} AND check_no=#{check} "
                +"AND seat_no=#{seat} AND version=#{version}")
        int save(@Param("entity") String entity,@Param("check") String check,@Param("seat") String seat,
                @Param("version") long version,@Param("label") String label,@Param("deleted") int deleted,
                @Param("actor") String actor,@Param("at") LocalDateTime at);
    }
    public interface TableOperationMapper extends IndustryTableMapper<FnbTableOperation> {
        // Intent facts cannot be ignored just because a projection was logically archived.
        @Select("SELECT COUNT(*) FROM fnb_table_operation WHERE entity_no=#{entity} AND (check_no=#{check} OR target_check_no=#{check}) "
                +"AND (resource_operation_ref IS NOT NULL OR status IS NULL OR status NOT IN ('APPLIED','FAILED'))")
        long externalOrPendingForCheck(@Param("entity") String entity,@Param("check") String check);
    }

    public interface ReservationMapper extends IndustryTableMapper<FnbReservation> { }

    public interface ActionLogMapper extends BaseMapper<FnbActionLog> {
    }

    public interface ServiceCalendarMapper extends IndustryTableMapper<FnbServiceCalendar> {
    }

    /** 门店当前策略：每店每行业一行（不保留历史，TDD §2.5）。 */
    public interface StorePolicyMapper extends IndustryTableMapper<FnbStorePolicy> {
        default FnbStorePolicy current(String entity, String store) {
            return first(entity, store, "LIMIT 1");
        }

        /** 锁住门店策略行：同一门店需要串行的业务域操作以它为锁（只在行业事务里用）。 */
        default FnbStorePolicy lockCurrent(String entity, String store) {
            return first(entity, store, "LIMIT 1 FOR UPDATE");
        }

        private FnbStorePolicy first(String entity, String store, String last) {
            var rows = selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<FnbStorePolicy>lambdaQuery()
                    .eq(FnbStorePolicy::getTenantNo, "MAIN")
                    .eq(FnbStorePolicy::getEntityNo, entity)
                    .eq(FnbStorePolicy::getStoreNo, store)
                    .eq(FnbStorePolicy::getIndustryCode, FnbStorePolicy.INDUSTRY)
                    .eq(FnbStorePolicy::getDeleted, 0)
                    .last(last));
            return rows.isEmpty() ? null : rows.getFirst();
        }
    }

    public interface TableMapper extends IndustryTableMapper<FnbTable> {
        @Select("SELECT COUNT(*) FROM fnb_table_occupancy WHERE entity_no=#{entity} "
                + "AND table_no=#{table} AND ended_at IS NULL")
        long liveOccupancies(@Param("entity") String entity, @Param("table") String table);

        @Update("UPDATE fnb_table SET deleted=1,version=version+1,updated_at=CURRENT_TIMESTAMP(3),updated_by=#{actor} "
                + "WHERE entity_no=#{entity} AND store_no=#{store} AND table_no=#{table} "
                + "AND version=#{version} AND deleted=0")
        int archive(@Param("entity") String entity, @Param("store") String store, @Param("table") String table,
                @Param("version") long version, @Param("actor") String actor);
    }

    public interface TableAreaMapper extends IndustryTableMapper<FnbTableArea> {
        @Select("SELECT COUNT(*) FROM fnb_table t WHERE t.entity_no=#{entity} AND t.store_no=#{store} "
                + "AND t.area_no=#{area} AND t.deleted=0 AND t.status='ACTIVE'")
        long activeTables(@Param("entity") String entity, @Param("store") String store, @Param("area") String area);

        // An anomalous occupancy must still block even if its table was disabled or logically deleted.
        @Select("SELECT COUNT(*) FROM fnb_table t JOIN fnb_table_occupancy o ON o.table_no=t.table_no "
                + "AND o.entity_no=t.entity_no WHERE t.entity_no=#{entity} AND t.store_no=#{store} "
                + "AND t.area_no=#{area} AND o.ended_at IS NULL")
        long liveOccupancies(@Param("entity") String entity, @Param("store") String store, @Param("area") String area);
    }
    public interface MenuContextMapper extends TradeTableMapper<FnbMenuContext> {
        @Delete("DELETE FROM fnb_menu_context WHERE entity_no=#{entity} AND menu_no=#{menu}")
        int replaceDraftRows(@Param("entity") String entity, @Param("menu") String menu);
    }
    public interface MenuChannelBindingMapper extends TradeTableMapper<FnbMenuChannelBinding> { }
    public interface StationMapper extends IndustryTableMapper<FnbStation> {
        @Select("SELECT id FROM ind_store_policy WHERE tenant_no='MAIN' AND entity_no=#{entity} "
                + "AND store_no=#{store} AND industry_code='FNB' AND deleted=0 ORDER BY id FOR UPDATE")
        List<Long> lockStore(@Param("entity") String entity, @Param("store") String store);
    }
    public interface SkuStationMapper extends TradeTableMapper<FnbSkuStation> {
        @Select("SELECT * FROM fnb_sku_station WHERE entity_no=#{entity} AND store_no=#{store} "
                + "AND sku_no=#{sku} ORDER BY id FOR UPDATE")
        List<FnbSkuStation> lockRoutes(@Param("entity") String entity, @Param("store") String store,
                @Param("sku") String sku);
        @Select("SELECT * FROM fnb_sku_station WHERE entity_no=#{entity} AND store_no=#{store} "
                + "AND sku_no=#{sku} ORDER BY id")
        List<FnbSkuStation> readRoutes(@Param("entity") String entity, @Param("store") String store,
                @Param("sku") String sku);
        @Update("UPDATE fnb_sku_station SET route_version=#{next},deleted=#{deleted},version=version+1 "
                + "WHERE entity_no=#{entity} AND store_no=#{store} AND sku_no=#{sku} AND id=#{id}")
        int retainRoute(@Param("entity") String entity, @Param("store") String store,
                @Param("sku") String sku, @Param("id") Long id, @Param("next") long next,
                @Param("deleted") int deleted);
        @Select("SELECT * FROM fnb_sku_station WHERE entity_no=#{entity} AND store_no=#{store} "
                + "AND station_no=#{station} AND deleted=0 ORDER BY sku_no,task_role")
        List<FnbSkuStation> stationRoutes(@Param("entity") String entity, @Param("store") String store,
                @Param("station") String station);
    }

    /** Explicit owner predicates are retained even for internal calls without a request data scope. */
    public interface StockSyncConfigMapper extends BaseMapper<ai.neargo.shop.food.entity.FnbStockSyncConfig> { }
    public interface StockSyncMappingMapper extends BaseMapper<ai.neargo.shop.food.entity.FnbStockSyncMapping> { }
    public interface StockSyncItemStateMapper extends BaseMapper<ai.neargo.shop.food.entity.FnbStockSyncItemState> { }
    public interface StockSyncDeliveryMapper extends BaseMapper<FnbStockSyncDelivery> {
        @Select("""
            SELECT d.* FROM fnb_stock_sync_delivery d
            WHERE d.tenant_no='MAIN' AND d.deleted=0
              AND d.status IN ('PENDING','RETRYABLE','PROCESSING')
              AND (d.next_attempt_at IS NULL OR d.next_attempt_at<=#{now})
              AND (#{entity} IS NULL OR d.entity_no=#{entity})
              AND (#{store} IS NULL OR d.store_no=#{store})
              AND NOT EXISTS (SELECT 1 FROM fnb_stock_sync_delivery earlier
                WHERE earlier.tenant_no=d.tenant_no AND earlier.entity_no=d.entity_no
                  AND earlier.store_no=d.store_no AND earlier.deleted=0 AND earlier.id<d.id
                  AND earlier.status IN ('PENDING','RETRYABLE','PROCESSING'))
            ORDER BY d.id LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
        FnbStockSyncDelivery nextDelivery(
                @Param("entity") String entity,
                @Param("store") String store,
                @Param("now") LocalDateTime now);
    }
    public interface StockSyncReconciliationMapper extends BaseMapper<ai.neargo.shop.food.entity.FnbStockSyncReconciliation> { }
    public interface StockSyncDifferenceMapper extends BaseMapper<ai.neargo.shop.food.entity.FnbStockSyncDifference> { }
    public interface StockItemMapper extends BaseMapper<ai.neargo.shop.food.entity.FnbStockItem> { }
    public interface StockReservationMapper extends BaseMapper<ai.neargo.shop.food.entity.FnbStockReservation> { }
    public interface StockReservationItemMapper extends BaseMapper<ai.neargo.shop.food.entity.FnbStockReservationItem> { }
    public interface StockCommandMapper extends BaseMapper<ai.neargo.shop.food.entity.FnbStockCommand> { }
    public interface StockMovementMapper extends BaseMapper<ai.neargo.shop.food.entity.FnbStockMovement> { }

    public interface StockBalanceMapper extends BaseMapper<FnbStockBalance> {
        @Select("SELECT * FROM fnb_stock_balance WHERE tenant_no='MAIN' AND entity_no=#{entity} "
                + "AND store_no=#{store} AND item_no=#{item} AND deleted=0 FOR UPDATE")
        FnbStockBalance lockBalance(@Param("entity") String entity, @Param("store") String store,
                @Param("item") String item);

        @Update("UPDATE fnb_stock_balance SET on_hand=#{onHand},reserved_qty=#{reserved},version=version+1, "
                + "updated_at=CURRENT_TIMESTAMP(3),updated_by=#{actor} WHERE tenant_no='MAIN' "
                + "AND entity_no=#{entity} AND store_no=#{store} AND item_no=#{item} "
                + "AND version=#{version} AND deleted=0")
        int replaceLockedBalance(@Param("entity") String entity, @Param("store") String store,
                @Param("item") String item, @Param("version") long version,
                @Param("onHand") BigDecimal onHand, @Param("reserved") BigDecimal reserved,
                @Param("actor") String actor);
    }

    public interface SalesRestrictionMapper extends TradeTableMapper<FnbSalesRestriction> { }
    public interface SalesQuotaClaimMapper extends TradeTableMapper<FnbSalesQuotaClaim> { }
    public interface ReturnMapper extends TradeTableMapper<FnbReturn> { }
    public interface ReturnItemMapper extends TradeTableMapper<FnbReturnItem> {
        /** 已由厨房确认取消的主菜数量（原在出品单 Mapper 上；退菜是交易的事实，不在行业库）。 */
        @Select("SELECT COALESCE(SUM(i.qty),0) FROM fnb_return_item i JOIN fnb_return r "
                + "ON r.entity_no=i.entity_no AND r.return_no=i.return_no AND r.tenant_no=i.tenant_no "
                + "WHERE i.tenant_no='MAIN' AND i.entity_no=#{entity} AND i.food_line_no=#{line} "
                + "AND i.component_no IS NULL AND i.kitchen_disposition='CANCEL' "
                + "AND r.kitchen_status='HANDLED' AND i.deleted=0 AND r.deleted=0")
        BigDecimal cancelledMainQty(@Param("entity") String entity,@Param("line") String line);
    }

    public interface CourseMapper extends IndustryTableMapper<FnbCourse> { }
    /** 制作任务在行业库（ADR-068 修订一）：与出品单、服务事件、道次同库同事务。 */
    public interface KitchenTaskMapper extends IndustryTableMapper<FnbKitchenTask> {
        /** 仍有未完结制作任务的批次号；门店与接单状态回交易库按 {@link OrderBatchMapper#states} 取。 */
        @Select("<script>SELECT DISTINCT k.batch_no FROM fnb_kitchen_task t "
                + "JOIN fnb_kitchen_ticket k ON k.tenant_no=t.tenant_no AND k.entity_no=t.entity_no AND k.ticket_no=t.ticket_no "
                + "WHERE t.tenant_no='MAIN' AND t.entity_no=#{entity} "
                + "AND t.deleted=0 AND k.deleted=0 "
                + "AND k.status&lt;&gt;'CLOSED' AND t.status NOT IN ('SERVED','VOIDED') "
                + "<if test='station != null'>AND t.station_no=#{station} </if>"
                + "</script>")
        List<String> activeTaskBatchNos(@Param("entity") String entity, @Param("station") String station);
    }
    public interface KitchenTicketMapper extends IndustryTableMapper<FnbKitchenTicket> {
        @Select("SELECT " + ConfigRevisionMapper.COLUMNS + " FROM ind_config_revision r WHERE r.tenant_no='MAIN' "
                + "AND r.entity_no=#{entity} "
                + "AND r.config_kind='STATION' AND r.deleted=0 AND r.effective_at<=CURRENT_TIMESTAMP(3) "
                + "AND r.status<>'CANCELLED' ORDER BY r.policy_no,r.effective_at DESC,r.revision_index DESC")
        List<FnbConfigRevision> stationPublications(@Param("entity") String entity);
    }
    public interface KitchenEventMapper extends IndustryTableMapper<FnbKitchenEvent> {
        /**
         * 会触发出品耗料的厨房事件（制作环节的开始、补料、损耗），带所属批次号。
         * 批次的门店与接单状态在交易库、耗料处理状态在耗料表，由服务按批次号与事件号分别补查（不跨库联表）。
         */
        record ProductionCandidate(long sourceId, String entityNo, String sourceEventNo, String taskNo,
                String action, String batchNo) {}

        @Select("<script>SELECT e.id AS source_id,e.entity_no,e.event_no AS source_event_no,e.task_no,e.event_type AS action,"
                + "k.batch_no FROM fnb_kitchen_event e "
                + "JOIN fnb_kitchen_task t ON t.tenant_no=e.tenant_no AND t.entity_no=e.entity_no AND t.task_no=e.task_no "
                + "JOIN fnb_kitchen_ticket k ON k.tenant_no=t.tenant_no AND k.entity_no=t.entity_no AND k.ticket_no=t.ticket_no "
                + "WHERE e.tenant_no='MAIN' <if test='entity != null'>AND e.entity_no=#{entity} </if>"
                + "AND e.deleted=0 AND t.deleted=0 AND k.deleted=0 AND t.task_role='MAKE' "
                + "AND e.event_type IN ('START','MATERIAL_SUPPLEMENT','MATERIAL_WASTE') "
                + "AND e.id&gt;#{afterId} ORDER BY e.id LIMIT #{limit}</script>")
        List<ProductionCandidate> productionCandidates(@Param("entity") String entity,
                @Param("afterId") long afterId, @Param("limit") int limit);

        /** 这些点菜行上的厨房事件号：出品耗料按商品筛选用。 */
        @Select("<script>SELECT e.event_no FROM fnb_kitchen_event e "
                + "JOIN fnb_kitchen_task t ON t.tenant_no=e.tenant_no AND t.entity_no=e.entity_no "
                + "AND t.task_no=e.task_no AND t.deleted=0 "
                + "WHERE e.tenant_no='MAIN' AND e.entity_no=#{entity} AND e.deleted=0 AND t.food_line_no IN "
                + "<foreach collection='lines' item='l' open='(' separator=',' close=')'>#{l}</foreach>"
                + "</script>")
        List<String> eventNosForLines(@Param("entity") String entity, @Param("lines") Collection<String> lines);
    }

    public interface BusinessDayMapper extends TradeTableMapper<FnbBusinessDay> { }
    public interface CloseRunMapper extends BaseMapper<FnbCloseRun> { }
    public interface CloseBalanceMapper extends BaseMapper<FnbCloseBalance> { }
    public interface CloseExceptionMapper extends BaseMapper<FnbCloseException> { }
    public interface CloseAdjustmentMapper extends BaseMapper<FnbCloseAdjustment> { }

    public interface ProductionConsumptionMapper extends BaseMapper<FnbProductionConsumption> {
        // Both SQL statements share this exact predicate; EXISTS keeps multi-material rows from inflating total.
        String LIST_FILTER = " FROM fnb_production_consumption h WHERE h.tenant_no='MAIN' AND h.entity_no=#{entity} "
                + "AND h.store_no=#{store} AND h.deleted=0 AND h.business_date &gt;= #{query.from} "
                + "AND h.business_date &lt; #{query.to} "
                + "<if test='query.status != null'>AND h.status=#{query.status} </if>"
                + "<if test='query.itemRef != null'>AND EXISTS (SELECT 1 FROM fnb_production_consumption_line p "
                + "WHERE p.tenant_no=h.tenant_no AND p.entity_no=h.entity_no AND p.consumption_no=h.consumption_no "
                + "AND p.deleted=0 AND p.item_ref=#{query.itemRef}) </if>"
                // 按商品筛：服务先在交易库取该商品的点菜行、再到行业库取事件号，这里只按事件号过滤。
                + "<if test='events != null'>AND h.source_event_no IN "
                + "<foreach collection='events' item='ev' open='(' separator=',' close=')'>#{ev}</foreach> </if>";
        /** 这些事件的耗料处理状态；按主体、门店、事件号对上才算（没有行表示还没开始处理）。 */
        record EventState(String entityNo, String storeNo, String sourceEventNo, String status) {}

        @Select("<script>SELECT entity_no,store_no,source_event_no,status FROM fnb_production_consumption "
                + "WHERE tenant_no='MAIN' AND deleted=0 AND source_event_no IN "
                + "<foreach collection='events' item='ev' open='(' separator=',' close=')'>#{ev}</foreach>"
                + "</script>")
        List<EventState> eventStates(@Param("events") Collection<String> events);

        @Select("<script>SELECT COUNT(*)" + LIST_FILTER + "</script>")
        long countFiltered(@Param("entity") String entity,@Param("store") String store,
                @Param("query") ListQuery query, @Param("events") Collection<String> events);
        @Select("<script>SELECT h.*" + LIST_FILTER
                + " ORDER BY h.occurred_at DESC,h.id DESC LIMIT #{size} OFFSET #{offset}</script>")
        List<FnbProductionConsumption> pageFiltered(@Param("entity") String entity,@Param("store") String store,
                @Param("query") ListQuery query, @Param("events") Collection<String> events,
                @Param("offset") long offset,@Param("size") int size);
    }
    public interface ProductionConsumptionLineMapper extends BaseMapper<FnbProductionConsumptionLine> { }
    public interface QueueTicketMapper extends IndustryTableMapper<FnbQueueTicket> { }
    public interface ReturnStockDispositionMapper extends BaseMapper<ai.neargo.shop.food.entity.FnbReturnStockDisposition> {}
}

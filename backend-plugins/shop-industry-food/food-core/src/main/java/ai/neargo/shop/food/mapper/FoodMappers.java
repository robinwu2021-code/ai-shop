package ai.neargo.shop.food.mapper;
import ai.neargo.shop.food.dto.ProductionContracts.ListQuery;
import ai.neargo.shop.food.entity.FnbProductionConsumptionLine;
import ai.neargo.shop.food.entity.FnbProductionConsumption;
import ai.neargo.shop.food.entity.FnbBusinessDay;
import ai.neargo.shop.food.entity.FnbCloseBalance;
import ai.neargo.shop.food.entity.FnbCloseRun;
import ai.neargo.shop.food.entity.FnbCloseException;
import ai.neargo.shop.food.entity.FnbCloseAdjustment;
import ai.neargo.shop.food.entity.FnbServiceEvent;
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
import java.time.LocalDateTime;
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

    public interface CheckCartMapper extends BaseMapper<FnbCheckCart> {
        @Select("SELECT COUNT(*) FROM fnb_order_batch WHERE tenant_no='MAIN' AND entity_no=#{entity} "
                + "AND cart_no=#{cart} AND deleted=0 AND status IN "
                + "('PREPARED','PLACING','UNKNOWN','BOUND','ACCEPTED','CANCEL_PENDING')")
        long activePlacementCount(@Param("entity") String entity, @Param("cart") String cart);
        @Select("SELECT COUNT(*) FROM fnb_order_batch b JOIN fnb_core_order_binding o "
                + "ON o.tenant_no=b.tenant_no AND o.entity_no=b.entity_no AND o.batch_no=b.batch_no "
                + "WHERE b.tenant_no='MAIN' AND b.entity_no=#{entity} AND b.store_no=#{store} "
                + "AND b.cart_no=#{cart} AND b.buyer_no=#{buyer} AND b.deleted=0 AND o.deleted=0 "
                + "AND b.status IN ('BOUND','ACCEPTED') AND o.status='BOUND'")
        long submittedBuyerBindingCount(@Param("entity") String entity, @Param("store") String store,
                @Param("cart") String cart, @Param("buyer") String buyer);
    }

    public interface SettlementMapper extends BaseMapper<FnbSettlement> {
        @Select("SELECT COUNT(*) FROM fnb_return WHERE tenant_no='MAIN' AND entity_no=#{entity} "
                + "AND batch_no=#{batch} AND deleted=0 AND status NOT IN ('REJECTED','CANCELLED') "
                + "AND (status<>'CLOSED' OR financial_status IS NULL OR financial_status<>'NONE')")
        long localReturnCount(@Param("entity") String entity, @Param("batch") String batch);
    }
    public interface SettlementItemMapper extends BaseMapper<FnbSettlementItem> {
        @Select("SELECT COUNT(*) FROM fnb_settlement_item i JOIN fnb_settlement s ON s.tenant_no=i.tenant_no "
                + "AND s.entity_no=i.entity_no AND s.settlement_no=i.settlement_no WHERE i.tenant_no='MAIN' "
                + "AND i.entity_no=#{entity} AND s.store_no=#{store} AND i.payment_root_no=#{root} "
                + "AND i.scope_key=#{scope} AND i.deleted=0 AND s.deleted=0 "
                + "AND s.status IN ('FROZEN','COLLECTING','SETTLED')")
        long activeFullScopeCount(@Param("entity") String entity, @Param("store") String store,
                @Param("root") String root, @Param("scope") String scope);
    }
    public interface ChargeIntentMapper extends BaseMapper<FnbChargeIntent> { }

    public interface OrderBatchMapper extends BaseMapper<FnbOrderBatch> { }
    public interface CoreOrderBindingMapper extends BaseMapper<FnbCoreOrderBinding> { }
    public interface OrderLineMapper extends BaseMapper<FnbOrderLine> { }
    public interface CoreLineAllocationMapper extends BaseMapper<FnbCoreLineAllocation> { }
    public interface OrderComponentMapper extends BaseMapper<FnbOrderComponent> { }
    public interface OrderModifierMapper extends BaseMapper<FnbOrderModifier> { }

    public interface CheckCartItemMapper extends BaseMapper<FnbCheckCartItem> {
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

    public interface MenuCategoryMapper extends BaseMapper<FnbMenuCategory> {
        @Delete("DELETE FROM fnb_menu_category WHERE entity_no=#{entity} AND menu_no=#{menu}")
        int replaceDraftRows(@Param("entity") String entity, @Param("menu") String menu);
    }

    public interface MenuItemMapper extends BaseMapper<FnbMenuItem> {
        @Delete("DELETE FROM fnb_menu_item WHERE entity_no=#{entity} AND category_no IN (SELECT categor"
                + "y_no FROM fnb_menu_category WHERE entity_no=#{entity} AND menu_no=#{menu})")
        int replaceDraftRows(@Param("entity") String entity, @Param("menu") String menu);
    }

    public interface MenuItemSkuMapper extends BaseMapper<FnbMenuItemSku> {
        @Delete("DELETE FROM fnb_menu_item_sku WHERE entity_no=#{entity} AND menu_item_no IN (SELECT me"
                + "nu_item_no FROM fnb_menu_item WHERE entity_no=#{entity} AND category_no IN (SELECT cat"
                + "egory_no FROM fnb_menu_category WHERE entity_no=#{entity} AND menu_no=#{menu}))")
        int replaceDraftRows(@Param("entity") String entity, @Param("menu") String menu);

        @Select("SELECT COUNT(*) FROM fnb_recipe WHERE entity_no=#{entity} AND store_no=#{store} "
                + "AND recipe_no=#{recipe} AND sku_no=#{sku} AND deleted=0 AND status IN ('DRAFT','ACTIVE')")
        long recipeForSku(@Param("entity") String entity, @Param("store") String store,
                @Param("recipe") String recipe, @Param("sku") String sku);
    }

    public interface MenuItemOptionGroupMapper extends BaseMapper<FnbMenuItemOptionGroup> {
        @Delete("DELETE FROM fnb_menu_item_option_group WHERE entity_no=#{entity} AND menu_item_no IN ("
                + "SELECT menu_item_no FROM fnb_menu_item WHERE entity_no=#{entity} AND category_no IN (S"
                + "ELECT category_no FROM fnb_menu_category WHERE entity_no=#{entity} AND menu_no=#{menu}"
                + "))")
        int replaceDraftRows(@Param("entity") String entity, @Param("menu") String menu);

        @Select("SELECT COUNT(*) FROM fnb_option_group WHERE entity_no=#{entity} AND group_no=#{group} "
                + "AND deleted=0 AND status IN ('DRAFT','ACTIVE')")
        long availableGroup(@Param("entity") String entity, @Param("group") String group);
    }

    public interface MenuScheduleMapper extends BaseMapper<FnbMenuSchedule> {
        @Delete("DELETE FROM fnb_menu_schedule WHERE entity_no=#{entity} AND menu_no=#{menu}")
        int replaceDraftRows(@Param("entity") String entity, @Param("menu") String menu);
    }
    public interface MenuExceptionMapper extends BaseMapper<FnbMenuException> {
        @Delete("DELETE FROM fnb_menu_exception WHERE entity_no=#{entity} AND menu_no=#{menu}")
        int replaceDraftRows(@Param("entity") String entity, @Param("menu") String menu);
    }

    public interface MenuMapper extends BaseMapper<FnbMenu> { }
    public interface MenuStoreMapper extends BaseMapper<FnbMenuStore> { }

    public interface RecipeMapper extends BaseMapper<FnbRecipe> { }
    public interface RecipeLineMapper extends BaseMapper<FnbRecipeLine> {
        @Delete("DELETE FROM fnb_recipe_line WHERE entity_no=#{entity} AND recipe_no=#{recipe}")
        int replaceDraftRows(@Param("entity") String entity, @Param("recipe") String recipe);
    }

    public interface BundleMapper extends BaseMapper<FnbBundle> { }
    public interface BundleGroupMapper extends BaseMapper<FnbBundleGroup> {
        // Only the locked mutable definition is replaceable; never a frozen publication.
        @Delete("DELETE FROM fnb_bundle_group WHERE entity_no=#{entity} AND bundle_sku_no=#{sku}")
        int replaceDraftRows(@Param("entity") String entity, @Param("sku") String sku);
    }
    public interface BundleChoiceMapper extends BaseMapper<FnbBundleChoice> {
        @Delete("DELETE FROM fnb_bundle_choice WHERE entity_no=#{entity} AND group_no IN "
                + "(SELECT group_no FROM fnb_bundle_group WHERE entity_no=#{entity} AND bundle_sku_no=#{sku})")
        int replaceDraftRows(@Param("entity") String entity, @Param("sku") String sku);
    }

    public interface DishOptionGroupMapper extends BaseMapper<FnbDishOptionGroup> {
        // Only the authorized current-store working association is replaced, never another store's binding.
        @Delete("DELETE FROM fnb_dish_option_group WHERE entity_no=#{entity} "
                + "AND group_no=#{group} AND goods_no=#{goods}")
        int removeDraftBinding(@Param("entity") String entity, @Param("group") String group,
                @Param("goods") String goods);
    }
    public interface OptionGroupMapper extends BaseMapper<FnbOptionGroup> { }
    public interface OptionGroupItemMapper extends BaseMapper<FnbOptionGroupItem> {
        // Working rows are replaceable; execution/history must use complete immutable revision snapshots.
        @Delete("DELETE FROM fnb_option_group_item WHERE entity_no=#{entity} AND group_no=#{group}")
        int replaceDraftRows(@Param("entity") String entity, @Param("group") String group);
    }
    /** No generic update/delete API is exposed for publication facts. */
    public interface ConfigRevisionMapper {
        // Fetch only timeline metadata; materialize large immutable JSON only for intersecting effective windows.
        @Select("SELECT r.revision_no,r.entity_no,r.config_kind,r.config_no,r.revision,r.effective_at,"
                + "r.status,r.scope_store_nos FROM fnb_config_revision r "
                + "JOIN fnb_menu_store ms ON ms.entity_no=r.entity_no AND ms.menu_no=r.config_no "
                + "AND ms.store_no=#{store} AND ms.deleted=0 "
                + "JOIN fnb_menu m ON m.entity_no=r.entity_no AND m.menu_no=r.config_no "
                + "AND m.deleted=0 AND m.status!='DISABLED' "
                + "WHERE r.entity_no=#{entity} AND r.config_kind='MENU' AND r.deleted=0 "
                + "AND r.status!='CANCELLED' ORDER BY r.config_no,r.effective_at,r.revision")
        List<FnbConfigRevision> menuTimeline(@Param("entity") String entity, @Param("store") String store);

        @Select("SELECT * FROM fnb_config_revision WHERE entity_no=#{entity} AND config_kind=#{kind} "
                + "AND config_no=#{number} AND deleted=0 ORDER BY revision DESC")
        List<FnbConfigRevision> findObject(@Param("entity") String entity, @Param("kind") String kind,
                @Param("number") String number);
        @Select("SELECT * FROM fnb_config_revision WHERE entity_no=#{entity} AND revision_no=#{number} AND deleted=0")
        FnbConfigRevision findRevision(@Param("entity") String entity, @Param("number") String number);
        @Insert("INSERT INTO fnb_config_revision(entity_no,created_at,created_by,updated_at,updated_by,"
                + "revision_no,config_kind,config_no,revision,content_schema,definition_snapshot,content_hash,"
                + "effective_at,status,previous_revision_no,scope_store_nos) VALUES(#{entityNo},CURRENT_TIMESTAMP(3),"
                + "#{createdBy},CURRENT_TIMESTAMP(3),#{createdBy},#{revisionNo},#{configKind},#{configNo},#{revision},"
                + "#{contentSchema},#{definitionSnapshot},#{contentHash},#{effectiveAt},#{status},"
                + "#{previousRevisionNo},#{scopeStoreNos})")
        int append(FnbConfigRevision row);
    }

    public interface ModifierMapper extends BaseMapper<FnbModifier> { }
    public interface ModifierComponentMapper extends BaseMapper<FnbModifierComponent> {
        // Replace only the editable composition, never a published revision or transaction fact.
        @Delete("DELETE FROM fnb_modifier_component WHERE entity_no=#{entity} AND combo_no=#{combo}")
        int replaceDraftRows(@Param("entity") String entity, @Param("combo") String combo);
    }

    public interface DishMapper extends BaseMapper<FnbDish> { }

    public interface TableTokenMapper extends BaseMapper<FnbTableToken> { }

    public interface GuestSessionMapper extends BaseMapper<FnbGuestSession> { }

    public interface CheckMapper extends BaseMapper<FnbCheck> {
        // Presence is intentionally fail-closed, including historical or anomalously deleted facts.
        @Select("SELECT (SELECT COUNT(*) FROM fnb_order_batch WHERE entity_no=#{entity} AND check_no=#{check})"
                +"+(SELECT COUNT(*) FROM fnb_check_batch_membership WHERE entity_no=#{entity} AND check_no=#{check})"
                +"+(SELECT COUNT(*) FROM fnb_settlement_item WHERE entity_no=#{entity} AND check_no=#{check})"
                +"+(SELECT COUNT(*) FROM fnb_return WHERE entity_no=#{entity} AND check_no=#{check})"
                +"+(SELECT COUNT(*) FROM fnb_dining_entitlement WHERE entity_no=#{entity} AND check_no=#{check})"
                +"+(SELECT COUNT(*) FROM fnb_check_cart WHERE entity_no=#{entity} AND check_no=#{check} AND (status IS NULL OR status<>'CLOSED'))")
        long tradeFacts(@Param("entity") String entity,@Param("check") String check);
    }
    public interface TableOccupancyMapper extends BaseMapper<FnbTableOccupancy> {
        @Select("SELECT * FROM fnb_table_occupancy WHERE entity_no=#{entity} AND table_no=#{table} AND ended_at IS NULL ORDER BY occupancy_no")
        List<FnbTableOccupancy> activeForTable(@Param("entity") String entity,@Param("table") String table);
        @Select("SELECT * FROM fnb_table_occupancy WHERE entity_no=#{entity} AND check_no=#{check} AND ended_at IS NULL ORDER BY occupancy_no")
        List<FnbTableOccupancy> activeForCheck(@Param("entity") String entity,@Param("check") String check);
    }
    public interface SeatMapper extends BaseMapper<FnbSeat> {
        @Select("SELECT * FROM fnb_seat WHERE entity_no=#{entity} AND check_no=#{check} ORDER BY seat_no")
        List<FnbSeat> history(@Param("entity") String entity,@Param("check") String check);
        @Update("UPDATE fnb_seat SET label=#{label},guest_ref=NULL,deleted=#{deleted},version=version+1,"
                +"updated_by=#{actor},updated_at=#{at} WHERE entity_no=#{entity} AND check_no=#{check} "
                +"AND seat_no=#{seat} AND version=#{version}")
        int save(@Param("entity") String entity,@Param("check") String check,@Param("seat") String seat,
                @Param("version") long version,@Param("label") String label,@Param("deleted") int deleted,
                @Param("actor") String actor,@Param("at") LocalDateTime at);
    }
    public interface TableOperationMapper extends BaseMapper<FnbTableOperation> {
        // Intent facts cannot be ignored just because a projection was logically archived.
        @Select("SELECT COUNT(*) FROM fnb_table_operation WHERE entity_no=#{entity} AND (check_no=#{check} OR target_check_no=#{check}) "
                +"AND (resource_operation_ref IS NOT NULL OR status IS NULL OR status NOT IN ('APPLIED','FAILED'))")
        long externalOrPendingForCheck(@Param("entity") String entity,@Param("check") String check);
    }

    public interface ReservationMapper extends BaseMapper<FnbReservation> { }

    public interface ActionLogMapper extends BaseMapper<FnbActionLog> {
    }

    public interface ServiceCalendarMapper extends BaseMapper<FnbServiceCalendar> {
    }

    public interface StorePolicyMapper extends BaseMapper<FnbStorePolicy> {
    }

    public interface TableMapper extends BaseMapper<FnbTable> {
        @Select("SELECT COUNT(*) FROM fnb_table_occupancy WHERE entity_no=#{entity} "
                + "AND table_no=#{table} AND ended_at IS NULL")
        long liveOccupancies(@Param("entity") String entity, @Param("table") String table);

        @Update("UPDATE fnb_table SET deleted=1,version=version+1,updated_at=CURRENT_TIMESTAMP(3),updated_by=#{actor} "
                + "WHERE entity_no=#{entity} AND store_no=#{store} AND table_no=#{table} "
                + "AND version=#{version} AND deleted=0")
        int archive(@Param("entity") String entity, @Param("store") String store, @Param("table") String table,
                @Param("version") long version, @Param("actor") String actor);
    }

    public interface TableAreaMapper extends BaseMapper<FnbTableArea> {
        @Select("SELECT COUNT(*) FROM fnb_table t WHERE t.entity_no=#{entity} AND t.store_no=#{store} "
                + "AND t.area_no=#{area} AND t.deleted=0 AND t.status='ACTIVE'")
        long activeTables(@Param("entity") String entity, @Param("store") String store, @Param("area") String area);

        // An anomalous occupancy must still block even if its table was disabled or logically deleted.
        @Select("SELECT COUNT(*) FROM fnb_table t JOIN fnb_table_occupancy o ON o.table_no=t.table_no "
                + "AND o.entity_no=t.entity_no WHERE t.entity_no=#{entity} AND t.store_no=#{store} "
                + "AND t.area_no=#{area} AND o.ended_at IS NULL")
        long liveOccupancies(@Param("entity") String entity, @Param("store") String store, @Param("area") String area);
    }
    public interface MenuContextMapper extends BaseMapper<FnbMenuContext> {
        @Delete("DELETE FROM fnb_menu_context WHERE entity_no=#{entity} AND menu_no=#{menu}")
        int replaceDraftRows(@Param("entity") String entity, @Param("menu") String menu);
    }
    public interface MenuChannelBindingMapper extends BaseMapper<FnbMenuChannelBinding> { }
    public interface StationMapper extends BaseMapper<FnbStation> {
        @Select("SELECT id FROM fnb_store_policy WHERE entity_no=#{entity} AND store_no=#{store} "
                + "AND deleted=0 ORDER BY id FOR UPDATE")
        List<Long> lockStore(@Param("entity") String entity, @Param("store") String store);
    }
    public interface SkuStationMapper extends BaseMapper<FnbSkuStation> {
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

    public interface SalesRestrictionMapper extends BaseMapper<FnbSalesRestriction> { }
    public interface SalesQuotaClaimMapper extends BaseMapper<FnbSalesQuotaClaim> { }
    public interface ReturnMapper extends BaseMapper<FnbReturn> { }
    public interface ReturnItemMapper extends BaseMapper<FnbReturnItem> { }

    public interface CourseMapper extends BaseMapper<FnbCourse> { }
    public interface KitchenTaskMapper extends BaseMapper<FnbKitchenTask> {
        @Select("<script>SELECT b.batch_no FROM fnb_kitchen_task t "
                + "JOIN fnb_kitchen_ticket k ON k.tenant_no=t.tenant_no AND k.entity_no=t.entity_no AND k.ticket_no=t.ticket_no "
                + "JOIN fnb_order_batch b ON b.tenant_no=k.tenant_no AND b.entity_no=k.entity_no AND b.batch_no=k.batch_no "
                + "WHERE t.tenant_no='MAIN' AND t.entity_no=#{entity} AND b.store_no=#{store} "
                + "AND t.deleted=0 AND k.deleted=0 AND b.deleted=0 AND b.status='ACCEPTED' "
                + "AND k.status&lt;&gt;'CLOSED' AND t.status NOT IN ('SERVED','VOIDED') "
                + "<if test='station != null'>AND t.station_no=#{station} </if>"
                + "GROUP BY b.batch_no,b.accepted_at ORDER BY b.accepted_at,b.batch_no</script>")
        List<String> activeBatchNos(@Param("entity") String entity,@Param("store") String store,
                @Param("station") String station);
    }
    public interface KitchenTicketMapper extends BaseMapper<FnbKitchenTicket> {
        @Select("SELECT COALESCE(SUM(i.qty),0) FROM fnb_return_item i JOIN fnb_return r "
                + "ON r.entity_no=i.entity_no AND r.return_no=i.return_no AND r.tenant_no=i.tenant_no "
                + "WHERE i.tenant_no='MAIN' AND i.entity_no=#{entity} AND i.food_line_no=#{line} "
                + "AND i.component_no IS NULL AND i.kitchen_disposition='CANCEL' "
                + "AND r.kitchen_status='HANDLED' AND i.deleted=0 AND r.deleted=0")
        BigDecimal cancelledMainQty(@Param("entity") String entity,@Param("line") String line);

        @Select("SELECT * FROM fnb_config_revision r WHERE r.tenant_no='MAIN' AND r.entity_no=#{entity} "
                + "AND r.config_kind='STATION' AND r.deleted=0 AND r.effective_at<=CURRENT_TIMESTAMP(3) "
                + "AND r.status<>'CANCELLED' ORDER BY r.config_no,r.effective_at DESC,r.revision DESC")
        List<FnbConfigRevision> stationPublications(@Param("entity") String entity);
    }
    public interface ServiceEventMapper extends BaseMapper<FnbServiceEvent> { }

    public interface BusinessDayMapper extends BaseMapper<FnbBusinessDay> { }
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
                + "<if test='query.skuNo != null'>AND EXISTS (SELECT 1 FROM fnb_service_event e "
                + "JOIN fnb_kitchen_task t ON t.tenant_no=e.tenant_no AND t.entity_no=e.entity_no "
                + "AND t.task_no=e.task_no AND t.deleted=0 "
                + "JOIN fnb_order_line l ON l.tenant_no=t.tenant_no AND l.entity_no=t.entity_no "
                + "AND l.food_line_no=t.food_line_no AND l.deleted=0 "
                + "JOIN fnb_order_batch b ON b.tenant_no=l.tenant_no AND b.entity_no=l.entity_no "
                + "AND b.batch_no=l.batch_no AND b.deleted=0 AND b.store_no=h.store_no "
                + "WHERE e.tenant_no=h.tenant_no AND e.entity_no=h.entity_no AND e.event_no=h.source_event_no "
                + "AND e.deleted=0 AND l.sku_no=#{query.skuNo}) </if>";
        @Select("<script>SELECT COUNT(*)" + LIST_FILTER + "</script>")
        long countFiltered(@Param("entity") String entity,@Param("store") String store,
                @Param("query") ListQuery query);
        @Select("<script>SELECT h.*" + LIST_FILTER
                + " ORDER BY h.occurred_at DESC,h.id DESC LIMIT #{size} OFFSET #{offset}</script>")
        List<FnbProductionConsumption> pageFiltered(@Param("entity") String entity,@Param("store") String store,
                @Param("query") ListQuery query,
                @Param("offset") long offset,@Param("size") int size);
    }
    public interface ProductionConsumptionLineMapper extends BaseMapper<FnbProductionConsumptionLine> { }
    public interface QueueTicketMapper extends BaseMapper<FnbQueueTicket> { }
}

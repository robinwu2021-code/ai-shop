package ai.neargo.shop.food.mapper;

import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.entity.FnbRecipe;
import ai.neargo.shop.food.entity.FnbRecipeLine;
import ai.neargo.shop.food.entity.FnbBundle;
import ai.neargo.shop.food.entity.FnbBundleGroup;
import ai.neargo.shop.food.entity.FnbBundleChoice;
import ai.neargo.shop.food.entity.FnbDish;
import ai.neargo.shop.food.entity.FnbDishOptionGroup;
import ai.neargo.shop.food.entity.FnbOptionGroup;
import ai.neargo.shop.food.entity.FnbModifier;
import ai.neargo.shop.food.entity.FnbConfigRevision;
import ai.neargo.shop.food.entity.FnbModifierComponent;
import ai.neargo.shop.food.entity.FnbOptionGroupItem;

import ai.neargo.shop.food.entity.FnbCheck;
import ai.neargo.shop.food.entity.FnbGuestSession;
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
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 按实际落地的持久化资源增加嵌套 Mapper，不预生成全域空壳。 */
public final class FoodMappers {
    private FoodMappers() {
    }

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
}

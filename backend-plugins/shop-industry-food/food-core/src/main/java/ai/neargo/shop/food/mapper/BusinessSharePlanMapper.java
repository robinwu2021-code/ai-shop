package ai.neargo.shop.food.mapper;

import ai.neargo.shop.food.dto.BusinessShareContracts.MembershipScope;
import ai.neargo.shop.food.entity.FnbBusinessSharePlan;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Current service membership takes precedence over the immutable originating check of each batch.
 */
public interface BusinessSharePlanMapper extends BaseMapper<FnbBusinessSharePlan> {
    @Select(
            "SELECT b.batch_no FROM fnb_order_batch b WHERE b.tenant_no='MAIN' AND b.deleted=0 AND"
                + " b.entity_no=#{entity} AND b.store_no=#{store} AND (EXISTS (SELECT 1 FROM"
                + " fnb_check_line_membership lm WHERE lm.entity_no=b.entity_no AND"
                + " lm.batch_no=b.batch_no AND lm.active_line_no IS NOT NULL AND"
                + " lm.check_no=#{check}) OR EXISTS (SELECT 1 FROM fnb_order_line l WHERE"
                + " l.entity_no=b.entity_no AND l.batch_no=b.batch_no AND l.deleted=0 AND NOT"
                + " EXISTS (SELECT 1 FROM fnb_check_line_membership lm WHERE"
                + " lm.entity_no=l.entity_no AND lm.active_line_no=l.food_line_no) AND (EXISTS"
                + " (SELECT 1 FROM fnb_check_batch_membership m WHERE m.entity_no=b.entity_no AND"
                + " m.batch_no=b.batch_no AND m.detached_at IS NULL AND m.deleted=0 AND"
                + " m.check_no=#{check}) OR (b.check_no=#{check} AND NOT EXISTS (SELECT 1 FROM"
                + " fnb_check_batch_membership m WHERE m.entity_no=b.entity_no AND"
                + " m.batch_no=b.batch_no AND m.detached_at IS NULL AND m.deleted=0))))) ORDER BY"
                + " b.batch_no")
    List<String> currentBatchNos(
            @Param("entity") String entity,
            @Param("store") String store,
            @Param("check") String check);

    @Select(
            "SELECT membership_no AS membershipNo,check_no AS checkNo,batch_no AS"
                    + " batchNo,version,attached_at AS attachedAt,operation_no AS operationNo FROM"
                    + " fnb_check_batch_membership WHERE tenant_no='MAIN' AND deleted=0 AND"
                    + " entity_no=#{entity} AND batch_no=#{batch} AND detached_at IS NULL ORDER BY"
                    + " membership_no")
    List<MembershipScope> liveMemberships(
            @Param("entity") String entity, @Param("batch") String batch);

    @Select(
            "SELECT COUNT(DISTINCT r.return_no) FROM fnb_return r JOIN fnb_return_item i ON"
                + " i.tenant_no=r.tenant_no AND i.entity_no=r.entity_no AND i.return_no=r.return_no"
                + " JOIN fnb_order_line l ON l.tenant_no=i.tenant_no AND l.entity_no=i.entity_no"
                + " AND l.food_line_no=i.food_line_no WHERE r.tenant_no='MAIN' AND r.deleted=0 AND"
                + " i.deleted=0 AND l.deleted=0 AND r.entity_no=#{entity} AND r.store_no=#{store}"
                + " AND l.batch_no=#{batch} AND NOT (r.status IN ('REJECTED','CANCELLED') AND"
                + " r.financial_status IN ('NONE','NOT_REQUESTED')) AND NOT EXISTS (SELECT 1 FROM"
                + " fnb_return_amendment a WHERE a.entity_no=r.entity_no AND a.store_no=r.store_no"
                + " AND a.return_no=r.return_no AND a.status='CONFIRMED' AND a.deleted=0)")
    long blockingReturns(
            @Param("entity") String entity,
            @Param("store") String store,
            @Param("batch") String batch);

    @Select(
            "SELECT check_no FROM fnb_check_line_membership WHERE entity_no=#{entity} AND"
                    + " active_line_no=#{line}")
    List<String> lineOwners(@Param("entity") String entity, @Param("line") String line);
}

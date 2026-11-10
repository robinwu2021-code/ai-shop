package ai.neargo.shop.beauty.mapper;

import ai.neargo.shop.beauty.entity.BtyServiceSubmission;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Local workflow projections under the existing work-order/task roots. No external IO in these
 * operations.
 */
public interface TaskWorkflowMapper extends BaseMapper<BtyServiceSubmission> {
    @Select(
            """
SELECT * FROM bty_service_submission WHERE tenant_no='MAIN' AND action='TASK_FLOW' AND deleted=0
AND (status IN ('INTENDED','RESOURCE_APPLIED','CANCEL_REQUESTED') OR (status='APPLIED'
  AND JSON_UNQUOTE(JSON_EXTRACT(frozen_command,'$.request.action'))='END'
  AND JSON_EXTRACT(workflow_observation_json,'$.fulfilled')=false))
ORDER BY updated_at,id LIMIT #{limit}
""")
    List<BtyServiceSubmission> pending(@Param("limit") int limit);

    @Select(
            """
SELECT * FROM bty_service_submission WHERE tenant_no='MAIN' AND entity_no=#{entity}
AND store_no=#{store} AND work_order_no=#{workOrder} AND action='TASK_FLOW' AND deleted=0
AND JSON_UNQUOTE(JSON_EXTRACT(frozen_command,'$.start.action.context.taskNo'))=#{task}
ORDER BY id DESC
""")
    List<BtyServiceSubmission> history(
            @Param("entity") String entity,
            @Param("store") String store,
            @Param("workOrder") String workOrder,
            @Param("task") String task);

    @Update(
            """
UPDATE bty_service_submission SET workflow_observation_json=#{observation},status=#{status},
failure_code=#{reason},version=version+1,updated_by=#{actor},updated_at=#{at}
WHERE tenant_no='MAIN' AND entity_no=#{entity} AND store_no=#{store} AND submission_no=#{operation}
AND action='TASK_FLOW' AND deleted=0 AND version=#{version} AND status IN ('INTENDED','RESOURCE_APPLIED','CANCEL_REQUESTED','APPLIED')
""")
    int observe(
            @Param("entity") String entity,
            @Param("store") String store,
            @Param("operation") String operation,
            @Param("version") long version,
            @Param("observation") String observation,
            @Param("status") String status,
            @Param("reason") String reason,
            @Param("actor") String actor,
            @Param("at") LocalDateTime at);

    @Update(
            """
UPDATE bty_work_order SET status='IN_SERVICE',started_at=COALESCE(started_at,#{at}),
version=version+1,updated_by=#{actor},updated_at=#{at}
WHERE tenant_no='MAIN' AND entity_no=#{entity} AND store_no=#{store} AND work_order_no=#{workOrder}
AND version=#{version} AND scope_revision=#{scopeRevision} AND scope_frozen=0 AND deleted=0
AND status='OPEN'
""")
    int startRoot(
            @Param("entity") String entity,
            @Param("store") String store,
            @Param("workOrder") String workOrder,
            @Param("version") long version,
            @Param("scopeRevision") long scopeRevision,
            @Param("actor") String actor,
            @Param("at") LocalDateTime at);

    @Update(
            """
UPDATE bty_work_order SET status='SERVICE_FINISHED',finished_at=GREATEST(#{at},
(SELECT MAX(t.finished_at) FROM bty_service_task t WHERE t.tenant_no='MAIN' AND t.entity_no=#{entity}
    AND t.work_order_no=#{workOrder} AND t.deleted=0)),version=version+1,
updated_by=#{actor},updated_at=#{at}
WHERE tenant_no='MAIN' AND entity_no=#{entity} AND store_no=#{store} AND work_order_no=#{workOrder}
AND version=#{version} AND scope_revision=#{scopeRevision} AND scope_frozen=0 AND deleted=0
AND status='IN_SERVICE'
AND NOT EXISTS (SELECT 1 FROM bty_service_task t WHERE t.tenant_no='MAIN' AND t.entity_no=#{entity}
    AND t.work_order_no=#{workOrder} AND t.deleted=0 AND t.status NOT IN ('DONE','VOIDED'))
""")
    int finishRoot(
            @Param("entity") String entity,
            @Param("store") String store,
            @Param("workOrder") String workOrder,
            @Param("version") long version,
            @Param("scopeRevision") long scopeRevision,
            @Param("actor") String actor,
            @Param("at") LocalDateTime at);

    @Update(
            """
UPDATE bty_service_task SET version=version+1,updated_at=#{at},updated_by=#{actor}
WHERE tenant_no='MAIN' AND entity_no=#{entity} AND task_no=#{task} AND deleted=0 AND version=#{version}
AND status='IN_SERVICE' AND binding_revision=#{bindingRevision}
AND EXISTS (SELECT 1 FROM bty_work_order w WHERE w.tenant_no='MAIN' AND w.entity_no=#{entity}
    AND w.store_no=#{store} AND w.work_order_no=bty_service_task.work_order_no AND w.deleted=0
    AND w.scope_frozen=0 AND w.status='IN_SERVICE')
""")
    int advanceTask(
            @Param("entity") String entity,
            @Param("store") String store,
            @Param("task") String task,
            @Param("version") long version,
            @Param("bindingRevision") long bindingRevision,
            @Param("actor") String actor,
            @Param("at") LocalDateTime at);
}

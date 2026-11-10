package ai.neargo.shop.beauty.scenario;

import tools.jackson.databind.json.JsonMapper;

/** Structurally legal CHECK-isolation rows only; not proof of a business or Native original. */
final class TaskStartSchemaRows {
    private TaskStartSchemaRows() {}

    static void insert(BeautyExecutionFixture fixture, String no, String execution) {
        var json = JsonMapper.builder().build();
        var frozen = json.createObjectNode();
        frozen.put("schema", "TASK_START_V1");
        frozen.put("submissionNo", no);
        frozen.put("frozenHash", "a".repeat(64));
        for (String field :
                new String[] {
                    "originalPlanSchema",
                    "originalPlanJson",
                    "originalPlanHash",
                    "resourcePlanJson",
                    "resourcePlanHash",
                    "nativeKey",
                    "ackActionNo"
                }) frozen.put(field, "STRUCTURE_ONLY");
        frozen.putArray("sourceGroups").addObject();
        frozen.putArray("retentionRoster").addObject();
        frozen.putObject("retention");
        frozen.putNull("booking");
        var action = frozen.putObject("action");
        action.put("requestKeyHash", no);
        action.putObject("reference").put("executionNo", "EXEC");
        var context = action.putObject("context");
        context.put("workOrderNo", "WO");
        context.put("taskNo", "TASK");
        context.putObject("scope").put("tenantNo", "MAIN").put("entityNo", "E").put("storeNo", "S");
        var observation =
                json.createObjectNode()
                        .put("schema", "TASK_START_OBSERVATION_V1")
                        .put("version", 0)
                        .put("phase", "SOURCE_FROZEN")
                        .put("stopRequired", false)
                        .put("pendingReason", "CURRENT_HUMAN_REQUIRED")
                        .put("observationHash", "b".repeat(64));
        observation.putNull("nativeOriginal");
        observation.putNull("acknowledgement");
        observation.putNull("cancellation");
        fixture.seed(
                "bty_service_submission",
                "submission_no",
                no,
                "store_no",
                "S",
                "work_order_no",
                "WO",
                "action",
                "TASK_START",
                "request_key_hash",
                no,
                "payload_hash",
                "a".repeat(64),
                "status",
                "SOURCE_FROZEN",
                "execution_no",
                execution,
                "frozen_command",
                json.writeValueAsString(frozen),
                "action_observation_json",
                json.writeValueAsString(observation),
                "failure_code",
                "CURRENT_HUMAN_REQUIRED");
    }
}

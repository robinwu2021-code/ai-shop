package ai.neargo.shop.print.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("prn_simulation_run")
public class PrnSimulationRun extends PrnRow {
    @TableField(value = "simulation_no", updateStrategy = FieldStrategy.ALWAYS)
    private String simulationNo;

    @TableField(value = "store_no", updateStrategy = FieldStrategy.ALWAYS)
    private String storeNo;

    @TableField(value = "operation_no", updateStrategy = FieldStrategy.ALWAYS)
    private String operationNo;

    @TableField(value = "scene", updateStrategy = FieldStrategy.ALWAYS)
    private String scene;

    @TableField(value = "source_type", updateStrategy = FieldStrategy.ALWAYS)
    private String sourceType;

    @TableField(value = "source_ref", updateStrategy = FieldStrategy.ALWAYS)
    private String sourceRef;

    @TableField(value = "fixture_code", updateStrategy = FieldStrategy.ALWAYS)
    private String fixtureCode;

    @TableField(value = "fixture_version", updateStrategy = FieldStrategy.ALWAYS)
    private String fixtureVersion;

    @TableField(value = "input_snapshot", updateStrategy = FieldStrategy.ALWAYS)
    private String inputSnapshot;

    @TableField(value = "template_resolution_snapshot", updateStrategy = FieldStrategy.ALWAYS)
    private String templateResolutionSnapshot;

    @TableField(value = "document_snapshots", updateStrategy = FieldStrategy.ALWAYS)
    private String documentSnapshots;

    @TableField(value = "input_hash", updateStrategy = FieldStrategy.ALWAYS)
    private String inputHash;

    @TableField(value = "status", updateStrategy = FieldStrategy.ALWAYS)
    private String status;

    @TableField(value = "expected_results", updateStrategy = FieldStrategy.ALWAYS)
    private String expectedResults;

    @TableField(value = "actual_results", updateStrategy = FieldStrategy.ALWAYS)
    private String actualResults;

    @TableField(value = "event_journal", updateStrategy = FieldStrategy.ALWAYS)
    private String eventJournal;

    @TableField(value = "claim_token_hash", updateStrategy = FieldStrategy.ALWAYS)
    private String claimTokenHash;

    @TableField(value = "lease_until", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime leaseUntil;

    @TableField(value = "next_run_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime nextRunAt;

    @TableField(value = "last_error_code", updateStrategy = FieldStrategy.ALWAYS)
    private String lastErrorCode;

    @TableField(value = "finished_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime finishedAt;

    @TableField(value = "created_by", updateStrategy = FieldStrategy.ALWAYS)
    private String createdBy;
}

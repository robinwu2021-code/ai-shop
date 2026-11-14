package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Current accepted pointer only; immutable submission originals retain all historical proof. */
@Getter
@Setter
@TableName("bty_task_assignment_slot")
public class BtyTaskAssignmentSlot extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String workOrderNo;
    private String taskNo;
    private String assignmentNo;
    private String requirementNo;
    private Integer roleOrdinal;
    private String roleCode;
    private String personnelNo;
    private String resourceNo;
    private String planKind;
    private String planNo;
    private Long planRevision;
    private String planHash;
    private String referenceHash;
    private String acceptActionNo;
    private String acceptRecordHash;
    private String qualificationHash;
    private Long assignmentRevision;
    private LocalDateTime acceptedAt;
    private LocalDateTime validUntil;
}

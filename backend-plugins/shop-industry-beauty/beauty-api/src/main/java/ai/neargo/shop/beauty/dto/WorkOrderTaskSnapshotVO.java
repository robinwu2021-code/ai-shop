package ai.neargo.shop.beauty.dto;

import java.util.List;

/** Server-loaded immutable service scope; displayed appointment prices are not a financial fact. */
public record WorkOrderTaskSnapshotVO(int schemaVersion, String goodsNo, String skuNo, String definitionVersionNo,
        String definitionHash, int durationMin, int bufferBeforeMin, int bufferAfterMin,
        List<WorkOrderRequirementVO> requirements, List<WorkOrderStepTemplateVO> stepsTemplate, List<String> notes) {
    public WorkOrderTaskSnapshotVO {
        requirements = List.copyOf(requirements); stepsTemplate = List.copyOf(stepsTemplate);
                notes = List.copyOf(notes);
    }
}

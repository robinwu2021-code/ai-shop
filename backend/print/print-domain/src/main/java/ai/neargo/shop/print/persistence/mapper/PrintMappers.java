package ai.neargo.shop.print.persistence.mapper;

import ai.neargo.shop.print.persistence.entity.*;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** Print-owned tables only. Business locks and CAS are applied by domain services. */
public final class PrintMappers {
    private PrintMappers() {}

    public interface PrnAgentMapper extends BaseMapper<PrnAgent> {}

    public interface PrnPrinterMapper extends BaseMapper<PrnPrinter> {}

    public interface PrnCredentialVersionMapper extends BaseMapper<PrnCredentialVersion> {}

    public interface PrnPrinterRuntimeMapper extends BaseMapper<PrnPrinterRuntime> {}

    public interface PrnTemplateMapper extends BaseMapper<PrnTemplate> {}

    public interface PrnTemplateVersionMapper extends BaseMapper<PrnTemplateVersion> {}

    public interface PrnRouteMapper extends BaseMapper<PrnRoute> {}

    public interface PrnRouteVersionMapper extends BaseMapper<PrnRouteVersion> {}

    public interface PrnScenePolicyMapper extends BaseMapper<PrnScenePolicy> {}

    public interface PrnPolicyVersionMapper extends BaseMapper<PrnPolicyVersion> {}

    public interface PrnRequestMapper extends BaseMapper<PrnRequest> {}

    public interface PrnBatchMapper extends BaseMapper<PrnBatch> {}

    public interface PrnJobMapper extends BaseMapper<PrnJob> {}

    public interface PrnAttemptMapper extends BaseMapper<PrnAttempt> {}

    public interface PrnReceiptMapper extends BaseMapper<PrnReceipt> {}

    public interface PrnOperationMapper extends BaseMapper<PrnOperation> {}

    public interface PrnIncidentMapper extends BaseMapper<PrnIncident> {}

    public interface PrnStoreStreamMapper extends BaseMapper<PrnStoreStream> {}

    public interface PrnActionLogMapper extends BaseMapper<PrnActionLog> {}

    public interface PrnReconcileCursorMapper extends BaseMapper<PrnReconcileCursor> {}

    public interface PrnSimulationRunMapper extends BaseMapper<PrnSimulationRun> {}

    public interface PrnExportTaskMapper extends BaseMapper<PrnExportTask> {}
}

package ai.neargo.shop.spi.report;

import java.time.Instant;
import java.util.Objects;

/** Private, actor-owned export; no public object URL or untrusted identity is returned. */
public interface ReportExportPort {
    enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED, EXPIRED }
    record Create(ReportQueryPort.Query query,String requestKey) {
        public Create { Objects.requireNonNull(query); ReportContracts.identifier(requestKey,128); }
    }
    record Reference(String tenantNo,String entityNo,String actorNo,String exportNo) {
        public Reference { ReportContracts.identifier(tenantNo,32); ReportContracts.identifier(entityNo,64);
            ReportContracts.identifier(actorNo,64); ReportContracts.identifier(exportNo,32); }
    }
    record Task(String exportNo,Status status,Instant expiresAt,Long rowCount,String checksum,String errorCode) {}
    record Download(String filename,String contentType,String checksum,byte[] content) {
        public Download { content=content.clone(); }
        @Override public byte[] content() { return content.clone(); }
    }
    record Snapshot(ReportQueryPort.Query query,ReportQueryPort.Result result) {}
    Task create(Create command);
    Task get(Reference reference);
    Download download(Reference reference);
}

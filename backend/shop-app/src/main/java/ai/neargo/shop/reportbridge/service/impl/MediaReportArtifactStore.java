package ai.neargo.shop.reportbridge.service.impl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import ai.neargo.shop.media.MediaStore;
import ai.neargo.shop.report.support.ReportCsvWriter;
import ai.neargo.shop.reportbridge.service.ReportArtifactStore;
import org.springframework.beans.factory.ObjectProvider;

/** Metadata is durably QUEUED/RUNNING in rpt_export_task before bytes are sent to private storage. */
public final class MediaReportArtifactStore implements ReportArtifactStore {
    private final ObjectProvider<MediaStore> store;
    public MediaReportArtifactStore(ObjectProvider<MediaStore> store) { this.store=store; }
    @Override public void put(String key,byte[] bytes) {
        validate(key);
        if (bytes.length>ReportCsvWriter.MAX_BYTES) throw new IllegalArgumentException("REPORT_EXPORT_TOO_LARGE");
        store.getObject().put(key,new ByteArrayInputStream(bytes),bytes.length,"text/csv; charset=UTF-8");
    }
    @Override public byte[] read(String key) {
        validate(key);
        try (var stream=store.getObject().openPrivate(key)) {
            var result=stream.readNBytes(ReportCsvWriter.MAX_BYTES+1);
            if (result.length>ReportCsvWriter.MAX_BYTES) throw new IllegalStateException("REPORT_ARTIFACT_TOO_LARGE");
            return result;
        } catch (IOException failure) { throw new IllegalStateException("REPORT_ARTIFACT_UNAVAILABLE"); }
    }
    @Override public void delete(List<String> keys) {
        keys.forEach(MediaReportArtifactStore::validate);store.getObject().delete(keys);
    }
    private static void validate(String key) {
        if (!key.matches("[A-Za-z0-9_-]{1,64}/_REPORT/reports/[A-Za-z0-9_-]{1,32}/[A-Za-z0-9_-]{1,80}\\.csv"))
            throw new IllegalArgumentException("REPORT_INVALID_ARTIFACT_KEY");
    }
}

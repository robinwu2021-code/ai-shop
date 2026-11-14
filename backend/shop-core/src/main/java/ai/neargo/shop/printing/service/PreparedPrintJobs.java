package ai.neargo.shop.printing.service;

import ai.neargo.shop.printing.entity.PrnJob;
import ai.neargo.shop.print.api.PrintHashes;
import ai.neargo.shop.print.api.PrintDocument;
import ai.neargo.shop.spi.printing.PrintPort;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** Adapts frozen rich output into the canonical task without replacing the legacy document contract. */
public final class PreparedPrintJobs {
    private PreparedPrintJobs() {}
    public static void initialize(PrnJob job) {
        job.setDeviceNo(job.getPrinterNo());
        job.setSourceEventNo(job.getRequestNo());
        job.setPartNo(job.getDocumentKey() == null ? job.getJobNo() : job.getDocumentKey().substring(0, Math.min(32,job.getDocumentKey().length())));
        job.setTemplateNo(job.getTemplateVersionNo()==null ? "BUILTIN" : job.getTemplateVersionNo());
        job.setDedupKey(PrintHashes.sha256("CENTER:"+job.getJobNo()));
        job.setRequestHash(job.getDocumentHash());
        job.setRequestedBy(job.getCreatedBy()==null ? "PRINT_CENTER" : job.getCreatedBy());
        job.setAttemptCount(0);
        job.setOriginalJobNo(job.getParentJobNo());
        // Read the frozen layout, never recompile or paginate historical documents.
        var json = new ObjectMapper();
        var layout = json.readValue(job.getLayoutSnapshot(), ai.neargo.shop.print.api.PrintLayout.class);
        var lines = new java.util.ArrayList<PrintPort.Line>();
        var rendered = new StringBuilder();
        for (var page : layout.pages()) for (var drawing : page.drawings()) {
            if (drawing instanceof ai.neargo.shop.print.api.PrintDrawing.TextRun text) {
                lines.add(new PrintPort.Line(text.text(), "", null, null));
                rendered.append(text.text()).append('\n');
            }
        }
        job.setDocumentSnapshot(json.writeValueAsString(new PrintPort.Document(1,job.getScene(),job.getJobNo(),
                job.getScene(),null,lines,"")));
        job.setRenderedContent(rendered.toString());
    }
}

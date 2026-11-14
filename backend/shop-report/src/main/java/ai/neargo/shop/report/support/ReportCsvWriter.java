package ai.neargo.shop.report.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import ai.neargo.shop.spi.report.ReportExportPort.Snapshot;

/** Bounded, precision-preserving CSV; text cells cannot become spreadsheet formulas. */
public final class ReportCsvWriter {
    public static final int MAX_BYTES=1048576;
    private ReportCsvWriter() {}
    public static byte[] write(Snapshot snapshot) {
        StringBuilder out=new StringBuilder("\uFEFF");
        line(out,List.of("rowType","storeNo","businessDate","metricCode","aggregationSet","attribution",
                "dimensions","value","currency","unit","coverage","knownSubtotal","reason"));
        line(out,List.of("METADATA","","","","","",ReportEvidenceCodec.json(snapshot.query()),"","","",
                snapshot.result().complete()?"COMPLETE":"INCOMPLETE","",ReportEvidenceCodec.json(snapshot.result().missingDays())));
        for (var row:snapshot.result().rows()) {
            var fact=row.fact(); var value=fact.value();
            String number=value.integerValue()!=null?value.integerValue().toString():value.amountMinor()!=null?value.amountMinor().toString()
                    :value.quantityValue()!=null?value.quantityValue().toPlainString():"";
            String subtotal=value.knownInteger()!=null?value.knownInteger().toString():value.knownAmount()!=null?value.knownAmount().toString()
                    :value.knownQuantity()!=null?value.knownQuantity().toPlainString():"";
            line(out,List.of("METRIC",row.scope().storeNo(),row.businessDate()==null?"":row.businessDate().toString(),fact.metricCode(),
                    fact.aggregationSet(),fact.attribution().name(),ReportEvidenceCodec.json(fact.dimensions()),number,
                    value.currency()==null?"":value.currency(),value.unit()==null?"":value.unit(),value.coverage().name(),subtotal,
                    value.unknownReason()==null?"":value.unknownReason()));
            if (out.length()>MAX_BYTES) throw new IllegalArgumentException("REPORT_EXPORT_TOO_LARGE");
        }
        line(out,List.of("PROVENANCE","","","","","",ReportEvidenceCodec.json(snapshot.result().builds()),"","","","",
                ReportEvidenceCodec.json(snapshot.result().sourceWatermarks())));
        for(var problem:snapshot.result().buildProblems()) line(out,List.of("BUILD_PROBLEM",problem.partition().scope().storeNo(),
                problem.partition().businessDate().toString(),"","","",ReportEvidenceCodec.json(problem),"","","",problem.status().name(),"",
                problem.errorCode()==null?"":problem.errorCode()));
        byte[] result=out.toString().getBytes(StandardCharsets.UTF_8);
        if (result.length>MAX_BYTES) throw new IllegalArgumentException("REPORT_EXPORT_TOO_LARGE");
        return result;
    }
    private static void line(StringBuilder target,List<String> cells) {
        for (int i=0;i<cells.size();i++) {
            if (i>0) target.append(',');
            String value=cells.get(i);
            String stripped=value.stripLeading();
            if (i!=7 && i!=11 && !stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0))>=0) value="'"+value;
            target.append('"').append(value.replace("\"","\"\"")).append('"');
        }
        target.append("\r\n");
    }
    public static String checksum(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}

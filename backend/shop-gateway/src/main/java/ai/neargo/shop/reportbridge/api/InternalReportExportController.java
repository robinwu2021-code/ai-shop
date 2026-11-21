package ai.neargo.shop.reportbridge.api;

import ai.neargo.shop.svc.ReportServiceResponses;
import org.springframework.web.bind.annotation.ExceptionHandler;

import ai.neargo.shop.reportbridge.service.ReportExportService;
import ai.neargo.shop.spi.report.ReportExportInternalApi;
import ai.neargo.shop.spi.report.ReportExportPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name={"shop.report.enabled","shop.report.food.enabled","shop.report.export.enabled"},havingValue="true")
@ConditionalOnProperty(name="shop.report.access-role",havingValue="WRITER",matchIfMissing=true)
public class InternalReportExportController {
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Void> reportFailure(RuntimeException failure) {
        return ReportServiceResponses.error(failure);
    }

    private final ReportExportService exports;
    private final String token;
    public InternalReportExportController(ReportExportService exports,@Value("${shop.services.internal-token:}") String token) {
        this.exports=exports; this.token=token;
    }
    @PostMapping(ReportExportInternalApi.CREATE)
    public ResponseEntity<?> create(@RequestBody ReportExportPort.Create query,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,()->exports.create(query));
    }
    @PostMapping(ReportExportInternalApi.GET)
    public ResponseEntity<?> get(@RequestBody ReportExportPort.Reference ref,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,()->exports.get(ref));
    }
    @PostMapping(ReportExportInternalApi.DOWNLOAD)
    public ResponseEntity<?> download(@RequestBody ReportExportPort.Reference ref,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,()->exports.download(ref));
    }
}

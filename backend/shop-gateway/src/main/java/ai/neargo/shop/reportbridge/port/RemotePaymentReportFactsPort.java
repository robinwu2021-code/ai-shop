package ai.neargo.shop.reportbridge.port;

import java.time.Duration;
import ai.neargo.shop.spi.report.ReportContracts.Owner;
import ai.neargo.shop.spi.report.ReportContracts.SourceKey;
import ai.neargo.shop.spi.report.ReportContracts.SourceQuery;
import ai.neargo.shop.spi.report.ReportContracts.SourceSnapshot;
import ai.neargo.shop.spi.report.ReportPaymentFactsInternalApi;
import ai.neargo.shop.spi.report.ReportSourceFactsPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;
import org.springframework.beans.factory.ObjectProvider;

public final class RemotePaymentReportFactsPort implements ReportSourceFactsPort {
    private final ObjectProvider<InternalHttp> http;
    public RemotePaymentReportFactsPort(ObjectProvider<InternalHttp> http) { this.http=http; }
    @Override public SourceKey source() { return new SourceKey(Owner.PAY,"PAYMENT"); }
    @Override public SourceSnapshot readSnapshot(SourceQuery query) {
        var api=http.getObject().client(ServiceName.PAY,ReportPaymentFactsInternalApi.class,Duration.ofSeconds(10));
        return ServiceCalls.call(ServiceName.PAY,()->api.snapshot(query));
    }
}

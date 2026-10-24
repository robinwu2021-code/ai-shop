package ai.neargo.shop.printing.port;

import ai.neargo.shop.printing.service.PrintService;
import ai.neargo.shop.spi.printing.PrintPort;
import java.util.List;

/** Cross-domain print contract delegates to its existing durable owner without changing receipts or delivery policy. */
public class PrintPortImpl implements PrintPort {
    private final PrintService service;

    public PrintPortImpl(PrintService service) { this.service = service; }

    @Override public Configuration configuration(Scope scope) { return service.configuration(scope); }
    @Override public DeviceVO bindDevice(Scope scope, DeviceReq request) { return service.bindDevice(scope, request); }
    @Override public TemplateReq publishTemplate(Scope scope, TemplateReq request) { return service.publishTemplate(scope, request); }
    @Override public RouteReq putRoute(Scope scope, RouteReq request) { return service.putRoute(scope, request); }
    @Override public List<JobVO> enqueue(Scope scope, Enqueue request) { return service.enqueue(scope, request); }
    @Override public List<JobVO> list(Scope scope, String afterJobNo, int limit) { return service.list(scope, afterJobNo, limit); }
    @Override public JobVO get(Scope scope, String jobNo) { return service.get(scope, jobNo); }
    @Override public JobVO retry(Scope scope, String jobNo) { return service.retry(scope, jobNo); }
    @Override public JobVO reprint(Scope scope, String jobNo, String reason, String requestKey) {
        return service.reprint(scope, jobNo, reason, requestKey);
    }
    @Override public DeviceVO heartbeat(Scope scope, String deviceNo, boolean online) {
        return service.heartbeat(scope, deviceNo, online);
    }
    @Override public Claim claim(Scope scope, String deviceNo) { return service.claim(scope, deviceNo); }
    @Override public JobVO acknowledge(Scope scope, String deviceNo, Acknowledge request) {
        return service.acknowledge(scope, deviceNo, request);
    }
}

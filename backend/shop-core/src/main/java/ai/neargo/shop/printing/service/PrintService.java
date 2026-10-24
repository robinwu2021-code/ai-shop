package ai.neargo.shop.printing.service;

import ai.neargo.shop.spi.printing.PrintPort.Scope;
import ai.neargo.shop.spi.printing.PrintPort.Configuration;
import ai.neargo.shop.spi.printing.PrintPort.DeviceVO;
import ai.neargo.shop.spi.printing.PrintPort.DeviceReq;
import ai.neargo.shop.spi.printing.PrintPort.TemplateReq;
import ai.neargo.shop.spi.printing.PrintPort.RouteReq;
import ai.neargo.shop.spi.printing.PrintPort.Enqueue;
import ai.neargo.shop.spi.printing.PrintPort.JobVO;
import ai.neargo.shop.spi.printing.PrintPort.Claim;
import ai.neargo.shop.spi.printing.PrintPort.Acknowledge;
import java.util.List;

/** Local owner contract keeps transport adapters separate from the durable delivery policy. */
public interface PrintService {
    Configuration configuration(Scope scope);
    DeviceVO bindDevice(Scope scope, DeviceReq request);
    TemplateReq publishTemplate(Scope scope, TemplateReq request);
    RouteReq putRoute(Scope scope, RouteReq request);
    List<JobVO> enqueue(Scope scope, Enqueue request);
    List<JobVO> list(Scope scope, String afterJobNo, int limit);
    JobVO get(Scope scope, String jobNo);
    JobVO retry(Scope scope, String jobNo);
    JobVO reprint(Scope scope, String jobNo, String reason, String requestKey);
    DeviceVO heartbeat(Scope scope, String deviceNo, boolean online);
    Claim claim(Scope scope, String deviceNo);
    JobVO acknowledge(Scope scope, String deviceNo, Acknowledge request);
}

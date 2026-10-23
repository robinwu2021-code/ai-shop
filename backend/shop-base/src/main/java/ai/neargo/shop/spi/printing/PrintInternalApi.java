package ai.neargo.shop.spi.printing;

import ai.neargo.shop.spi.printing.PrintPort.Scope;
import com.fasterxml.jackson.annotation.JsonProperty;
import ai.neargo.shop.spi.printing.PrintPort.DeviceReq;
import ai.neargo.shop.spi.printing.PrintPort.DeviceVO;
import ai.neargo.shop.spi.printing.PrintPort.TemplateReq;
import ai.neargo.shop.spi.printing.PrintPort.RouteReq;
import ai.neargo.shop.spi.printing.PrintPort.Configuration;
import ai.neargo.shop.spi.printing.PrintPort.Enqueue;
import ai.neargo.shop.spi.printing.PrintPort.JobVO;
import ai.neargo.shop.spi.printing.PrintPort.Claim;
import ai.neargo.shop.spi.printing.PrintPort.Acknowledge;
import java.util.List;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Service-token protected shared printing protocol for independently deployed industry adapters. */
public interface PrintInternalApi {
    /** A successful idle poll has an explicit envelope; missing response remains a transport error. */
    record ClaimResult(@JsonProperty(value="claim",required=true) Claim claim) { }

    String ROOT="/internal/printing";
    @PostExchange(ROOT+"/configuration") Configuration configuration(@RequestBody Scope scope);
    @PostExchange(ROOT+"/bindDevice") DeviceVO bindDevice(@RequestBody DeviceCommand request);
    @PostExchange(ROOT+"/publishTemplate") TemplateReq publishTemplate(@RequestBody TemplateCommand request);
    @PostExchange(ROOT+"/putRoute") RouteReq putRoute(@RequestBody RouteCommand request);
    @PostExchange(ROOT+"/enqueue") List<JobVO> enqueue(@RequestBody EnqueueCommand request);
    @PostExchange(ROOT+"/list") List<JobVO> list(@RequestBody ListQuery request);
    @PostExchange(ROOT+"/get") JobVO get(@RequestBody JobQuery request);
    @PostExchange(ROOT+"/retry") JobVO retry(@RequestBody JobQuery request);
    @PostExchange(ROOT+"/reprint") JobVO reprint(@RequestBody ReprintCommand request);
    @PostExchange(ROOT+"/heartbeat") DeviceVO heartbeat(@RequestBody HeartbeatCommand request);
    @PostExchange(ROOT+"/claim") ClaimResult claim(@RequestBody DeviceQuery request);
    @PostExchange(ROOT+"/acknowledge") JobVO acknowledge(@RequestBody AckCommand request);
    record DeviceCommand(Scope scope,DeviceReq definition) { }
    record TemplateCommand(Scope scope,TemplateReq definition) { }
    record RouteCommand(Scope scope,RouteReq definition) { }
    record EnqueueCommand(Scope scope,Enqueue definition) { }
    record ListQuery(Scope scope,String afterJobNo,int limit) { }
    record JobQuery(Scope scope,String jobNo) { }
    record ReprintCommand(Scope scope,String jobNo,String reason,String requestKey) { }
    record HeartbeatCommand(Scope scope,String deviceNo,boolean online) { }
    record DeviceQuery(Scope scope,String deviceNo) { }
    record AckCommand(Scope scope,String deviceNo,Acknowledge receipt) { }
}

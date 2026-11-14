package ai.neargo.shop.printing.api.internal;

import ai.neargo.shop.printing.service.PrintService;
import org.springframework.beans.factory.annotation.Autowired;

import ai.neargo.shop.spi.printing.PrintInternalApi;
import ai.neargo.shop.spi.printing.PrintPort;
import ai.neargo.shop.spi.printing.PrintPort.Scope;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal service tokens admit typed calls; shared service rechecks entity, store and bridge actor
 * binding.
 */
@RestController
public class InternalPrintController {
    private PrintService owner;
    @Autowired
    public void setOwner(PrintService owner) {this.owner=owner;}
    public record ResolveDeviceCommand(Scope scope, PrintService.DeviceResolution resolution) {}

    @PostMapping(PrintInternalApi.ROOT + "/resolveDeviceUncertainty")
    public ResponseEntity<?> resolveDeviceUncertainty(@RequestBody ResolveDeviceCommand request,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,()->owner.resolveDeviceUncertainty(request.scope(),request.resolution()));
    }

    public record RecoveryQuery(Scope scope,String deviceNo) {}
    @PostMapping(PrintInternalApi.ROOT + "/deviceRecovery")
    public ResponseEntity<?> deviceRecovery(@RequestBody RecoveryQuery request,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,()->owner.deviceRecovery(request.scope(),request.deviceNo()));
    }

    private final PrintPort service;
    private final String token;

    public InternalPrintController(
            PrintPort service, @Value("${shop.services.internal-token:}") String token) {
        this.service = service;
        this.token = token;
    }

    @PostMapping(PrintInternalApi.ROOT + "/configuration")
    public ResponseEntity<?> configuration(
            @RequestBody Scope scope,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> service.configuration(scope));
    }

    @PostMapping(PrintInternalApi.ROOT + "/bindDevice")
    public ResponseEntity<?> bindDevice(
            @RequestBody PrintInternalApi.DeviceCommand request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token, given, () -> service.bindDevice(request.scope(), request.definition()));
    }

    @PostMapping(PrintInternalApi.ROOT + "/revokeDevice")
    public ResponseEntity<?> revokeDevice(
            @RequestBody PrintInternalApi.RevokeDeviceCommand request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () ->
                        service.revokeDevice(
                                request.scope(),
                                request.deviceNo(),
                                request.reason(),
                                request.requestKey()));
    }

    @PostMapping(PrintInternalApi.ROOT + "/publishTemplate")
    public ResponseEntity<?> publishTemplate(
            @RequestBody PrintInternalApi.TemplateCommand request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token, given, () -> service.publishTemplate(request.scope(), request.definition()));
    }

    @PostMapping(PrintInternalApi.ROOT + "/putRoute")
    public ResponseEntity<?> putRoute(
            @RequestBody PrintInternalApi.RouteCommand request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token, given, () -> service.putRoute(request.scope(), request.definition()));
    }

    @PostMapping(PrintInternalApi.ROOT + "/enqueue")
    public ResponseEntity<?> enqueue(
            @RequestBody PrintInternalApi.EnqueueCommand request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token, given, () -> service.enqueue(request.scope(), request.definition()));
    }

    @PostMapping(PrintInternalApi.ROOT + "/list")
    public ResponseEntity<?> list(
            @RequestBody PrintInternalApi.ListQuery request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () -> service.list(request.scope(), request.afterJobNo(), request.limit()));
    }

    @PostMapping(PrintInternalApi.ROOT + "/get")
    public ResponseEntity<?> get(
            @RequestBody PrintInternalApi.JobQuery request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token, given, () -> service.get(request.scope(), request.jobNo()));
    }

    @PostMapping(PrintInternalApi.ROOT + "/retry")
    public ResponseEntity<?> retry(
            @RequestBody PrintInternalApi.JobQuery request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token, given, () -> service.retry(request.scope(), request.jobNo()));
    }

    @PostMapping(PrintInternalApi.ROOT + "/reprint")
    public ResponseEntity<?> reprint(
            @RequestBody PrintInternalApi.ReprintCommand request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () ->
                        service.reprint(
                                request.scope(),
                                request.jobNo(),
                                request.reason(),
                                request.requestKey()));
    }

    @PostMapping(PrintInternalApi.ROOT + "/heartbeat")
    public ResponseEntity<?> heartbeat(
            @RequestBody PrintInternalApi.HeartbeatCommand request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () -> service.heartbeat(request.scope(), request.deviceNo(), request.online()));
    }

    @PostMapping(PrintInternalApi.ROOT + "/claim")
    public ResponseEntity<?> claim(
            @RequestBody PrintInternalApi.DeviceQuery request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () ->
                        new PrintInternalApi.ClaimResult(
                                service.claim(request.scope(), request.deviceNo())));
    }

    @PostMapping(PrintInternalApi.ROOT + "/acknowledge")
    public ResponseEntity<?> acknowledge(
            @RequestBody PrintInternalApi.AckCommand request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () -> service.acknowledge(request.scope(), request.deviceNo(), request.receipt()));
    }
}

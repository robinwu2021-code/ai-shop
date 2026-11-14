package ai.neargo.shop.printing.center.service.impl;

import ai.neargo.shop.spi.printing.*;

import static ai.neargo.shop.printing.center.support.PrintSupport.*;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintDeviceDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.printing.center.dispatch.PrintAdapterRegistry;
import ai.neargo.shop.printing.center.layout.*;
import ai.neargo.shop.printing.center.operation.PrintOperationService;
import ai.neargo.shop.printing.entity.PrnJob;
import ai.neargo.shop.printing.center.persistence.entity.*;
import ai.neargo.shop.printing.mapper.PrintMappers.JobMapper;
import ai.neargo.shop.printing.center.persistence.mapper.PrintMappers.*;
import ai.neargo.shop.printing.center.support.*;
import ai.neargo.shop.printing.center.template.*;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
public class PrintDeviceServiceImpl implements PrintDeviceService {
    private ai.neargo.shop.printing.mapper.PrintMappers.DeviceMapper canonicalDevices;
    @org.springframework.beans.factory.annotation.Autowired
    public void setCanonicalDevices(ai.neargo.shop.printing.mapper.PrintMappers.DeviceMapper mapper) { canonicalDevices=mapper; }
    private final ai.neargo.shop.printing.center.service.PrintTestJobFactory testJobs;
    private final PrnPrinterMapper printers;
    private final PrnPrinterRuntimeMapper runtimes;
    private final PrnCredentialVersionMapper credentials;
    private final PrnAgentMapper agents;
    private final JobMapper jobs;
    private final PrnRouteVersionMapper routeVersions;
    private final PrintAdapterRegistry adapters;
    private final PrintCredentialCipherPort cipher;
    private final PrintOperationService ops;
    private final PrintJsonCodec json;

    public PrintDeviceServiceImpl(
            PrnPrinterMapper p,
            PrnPrinterRuntimeMapper r,
            PrnCredentialVersionMapper c,
            PrnAgentMapper a,
            JobMapper j,
            PrnRouteVersionMapper rv,
            PrintAdapterRegistry ar,
            PrintCredentialCipherPort cp,
            PrintOperationService o,
            PrintJsonCodec codec,
            ai.neargo.shop.printing.center.service.PrintTestJobFactory testJobs) {
        this.testJobs = testJobs;
        printers = p;
        runtimes = r;
        credentials = c;
        agents = a;
        jobs = j;
        routeVersions = rv;
        adapters = ar;
        cipher = cp;
        ops = o;
        json = codec;
    }

    private PrnPrinter printer(String no, PrintActor a) {
        var p = printers.selectOne(PrintSupport.<PrnPrinter>scoped(scope(a)).eq("printer_no", no));
        require(p != null, "print.notFound");
        return p;
    }

    private PrnPrinterRuntime runtime(String no, PrintActor a) {
        var r =
                runtimes.selectOne(
                        PrintSupport.<PrnPrinterRuntime>owned(scope(a))
                                .eq("printer_no", no)
                                .last("FOR UPDATE"));
        require(r != null, "print.runtimeMissing");
        return r;
    }

    private PrinterVO vo(PrnPrinter p) {
        return new PrinterVO(
                p.getPrinterNo(),
                p.getStoreNo(),
                p.getName(),
                p.getConnectionType(),
                p.getAdapterCode(),
                p.getPaperWidthMm(),
                p.getStatus(),
                p.getHealthStatus(),
                p.getRevision());
    }

    public List<AdapterVO> listAdapters(ListAdaptersQuery q, PrintActor a) {
        var list = new ArrayList<AdapterVO>();
        for (var p : adapters.all())
            if (q.connectionType() == null || "CLOUD".equals(q.connectionType()))
                list.add(new AdapterVO(p.adapterCode(), p.capabilities()));
        if (q.connectionType() == null || "LOCAL".equals(q.connectionType()))
            list.add(
                    new AdapterVO(
                            "LOCAL_SYSTEM_QUEUE",
                            new PrintAdapterCapabilities(
                                    "LOCAL_SYSTEM_QUEUE",
                                    false,
                                    false,
                                    true,
                                    false,
                                    Set.of(58, 80),
                                    Set.of(PrintEvidenceLevel.MANUAL_CONFIRMED))));
        return list;
    }

    public PageData<PrinterVO> listPrinters(ListPrintersQuery q, PrintActor a) {
        require(a.storeNo().equals(q.storeNo()), "print.forbidden");
        var p =
                printers.selectPage(
                        new Page<>(
                                pageNumber(q.page()),
                                pageSize(q.size())),
                        PrintSupport.<PrnPrinter>scoped(scope(a))
                                .eq(q.status() != null, "status", q.status())
                                .eq(q.healthStatus() != null, "health_status", q.healthStatus())
                                .eq(
                                        q.connectionType() != null,
                                        "connection_type",
                                        q.connectionType())
                                .orderByDesc("id"));
        return PageData.of(
                p.getRecords().stream().map(this::vo).toList(),
                p.getTotal(),
                p.getCurrent(),
                p.getSize());
    }

    public PrintOperation createPrinter(CreatePrinterReq r, PrintActor a) {
        return ops.execute(
                a,
                "CREATE_PRINTER",
                fingerprint(r),
                () -> {
                    require(a.storeNo().equals(r.storeNo()), "print.forbidden");
                    validate(r.name(), r.paperWidthMm());
                    var p = init(new PrnPrinter(), scope(a));
                    p.setPrinterNo(no("PP"));
                    p.setStoreNo(a.storeNo());
                    p.setName(r.name());
                    p.setPaperWidthMm(r.paperWidthMm());
                    p.setStatus("PAUSED");
                    p.setHealthStatus("UNKNOWN");
                    p.setConnectionRevision(1L);
                    configure(p, r.connection(), a);
                    syncCanonical(p);
                    printers.insert(p);
                    var rt = init(new PrnPrinterRuntime(), scope(a));
                    rt.setPrinterNo(p.getPrinterNo());
                    rt.setFenceEpoch(0L);
                    rt.setLastSequence(0L);
                    runtimes.insert(rt);
                    return succeeded(
                            "CREATE_PRINTER",
                            "PRINTER",
                            p.getPrinterNo(),
                            json.freeze(Map.of("printerNo", p.getPrinterNo())));
                });
    }

    private String fingerprint(Object r) {
        return json.freeze(r).sha256();
    }

    private void validate(String name, int width) {
        require(
                name != null
                        && !name.isBlank()
                        && name.length() <= 64
                        && (width == 58 || width == 80),
                "print.invalidPrinter");
    }

    private void configure(PrnPrinter p, Connection c, PrintActor a) {
        require(c != null, "print.invalidConnection");
        if(c.config()!=null) {
            var existingDevice=json.tree(c.config()).path("canonicalDeviceNo").asText("");
            if(!existingDevice.isBlank()) {
                var existing=canonicalDevices.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ai.neargo.shop.printing.entity.PrnDevice>()
                        .eq("tenant_no",a.tenantNo()).eq("entity_no",a.entityNo()).eq("store_no",a.storeNo())
                        .eq("device_no",existingDevice).last("FOR UPDATE"));
                require(existing!=null,"print.canonicalDeviceNotFound");
                require(p.getId()==null || existingDevice.equals(p.getPrinterNo()),"print.deviceIdentityImmutable");
                p.setPrinterNo(existingDevice);
            }
        }
        p.setConnectionType(c.type());
        p.setAdapterCode(c.adapterCode());
        if ("CLOUD".equals(c.type())) {
            var adapter = adapters.requireCloud(c.adapterCode());
            require(
                    adapter.capabilities().paperWidthsMm().contains(p.getPaperWidthMm()),
                    "print.paperMismatch");
            require(
                    c.vendorAccountRef() != null
                            && c.vendorAccountRef().matches("[A-Za-z0-9_@.:-]{1,64}")
                            && c.deviceSn() != null
                            && c.deviceSn().matches("[A-Za-z0-9_-]{1,128}"),
                    "print.invalidConnection");
            require(
                    c.credentials().get("user") != null
                            && c.vendorAccountRef().equals(c.credentials().get("user")),
                    "print.vendorAccountMismatch");
            p.setVendorAccountRef(c.vendorAccountRef());
            p.setDeviceSn(c.deviceSn());
            p.setAgentNo(null);
            p.setConnectionConfig("{}");
            p.setPhysicalKey(
                    "CLOUD:"
                            + c.adapterCode()
                            + ":"
                            + PrintHashes.sha256(c.deviceSn().toUpperCase(java.util.Locale.ROOT)));
            p.setCapabilities(json.write(adapter.capabilities()));
            resolveCanonicalIdentity(p,a);
            storeCredential(p, c.credentials(), a);
        } else {
            require(
                    "LOCAL".equals(c.type()) && "LOCAL_SYSTEM_QUEUE".equals(c.adapterCode()),
                    "print.invalidConnection");
            var agent =
                    agents.selectOne(
                            PrintSupport.<PrnAgent>scoped(scope(a))
                                    .eq("agent_no", c.agentNo())
                                    .eq("status", "ACTIVE"));
            require(
                    agent != null && c.config() != null && c.physicalRegistrationRef() != null,
                    "print.agentUnavailable");
            json.freeze(c.config());
            var queue = json.tree(c.config()).path("queueName").asText();
            require(
                    !queue.isBlank()
                            && queue.length() <= 128
                            && !queue.contains("\\")
                            && !queue.contains("://"),
                    "print.invalidQueue");
            var observed =
                    json.tree(agent.getCapabilities() == null ? "{}" : agent.getCapabilities())
                            .path("registeredDevices");
            require(
                    observed.has(c.physicalRegistrationRef())
                            && queue.equals(
                                    observed.path(c.physicalRegistrationRef())
                                            .path("queueName")
                                            .asText()),
                    "print.physicalRegistrationRequired");
            p.setPhysicalKey("LOCAL:" + PrintHashes.sha256(c.physicalRegistrationRef()));
            resolveCanonicalIdentity(p,a);
            p.setAgentNo(c.agentNo());
            p.setVendorAccountRef(null);
            p.setDeviceSn(null);
            p.setCredentialRef(null);
            p.setConnectionConfig(json.write(c.config()));
            p.setCapabilities(
                    json.write(
                            Map.of(
                                    "paperWidthsMm",
                                    List.of(p.getPaperWidthMm()),
                                    "evidenceLevels",
                                    List.of("MANUAL_CONFIRMED"))));
        }
    }

    private void resolveCanonicalIdentity(PrnPrinter p,PrintActor a) {
        var physical=canonicalDevices.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ai.neargo.shop.printing.entity.PrnDevice>()
                .eq("physical_key",p.getPhysicalKey()).last("FOR UPDATE"));
        if(physical!=null) {
            require(a.tenantNo().equals(physical.getTenantNo()) && a.entityNo().equals(physical.getEntityNo())
                    && a.storeNo().equals(physical.getStoreNo()),"print.physicalDeviceOwnedElsewhere");
            require(p.getId()==null || physical.getDeviceNo().equals(p.getPrinterNo()),"print.deviceIdentityImmutable");
            p.setPrinterNo(physical.getDeviceNo());
            return;
        }
        var selected=canonicalDevices.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ai.neargo.shop.printing.entity.PrnDevice>()
                .eq("tenant_no",a.tenantNo()).eq("entity_no",a.entityNo()).eq("store_no",a.storeNo())
                .eq("device_no",p.getPrinterNo()).last("FOR UPDATE"));
        if(selected!=null) {
            require(selected.getPhysicalKey()==null || selected.getPhysicalKey().equals(p.getPhysicalKey()),"print.deviceIdentityImmutable");
            return;
        }
        long unresolved=canonicalDevices.selectCount(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ai.neargo.shop.printing.entity.PrnDevice>()
                .eq("tenant_no",a.tenantNo()).eq("entity_no",a.entityNo()).eq("store_no",a.storeNo()).isNull("physical_key"));
        require(unresolved==0,"print.canonicalDeviceRequired");
    }

    private void storeCredential(PrnPrinter p, Map<String, String> input, PrintActor a) {
        require(
                input != null && input.containsKey("user") && input.containsKey("ukey"),
                "print.credentialsRequired");
        String ref = no("CV");
        var encrypted =
                cipher.encrypt(
                        scope(a),
                        ref,
                        json.write(input).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var c = init(new PrnCredentialVersion(), scope(a));
        c.setCredentialRef(ref);
        c.setPrinterNo(p.getPrinterNo());
        c.setVersionNumber(
                credentials
                                .selectCount(
                                        PrintSupport.<PrnCredentialVersion>owned(scope(a))
                                                .eq("printer_no", p.getPrinterNo()))
                                .intValue()
                        + 1);
        c.setCredentialCiphertext(Base64.getEncoder().encodeToString(encrypted.ciphertext()));
        c.setEncryptionKeyVersion(encrypted.encryptionKeyVersion());
        c.setStatus("ACTIVE");
        c.setCreatedBy(a.actorNo());
        credentials.insert(c);
        if (p.getCredentialRef() != null) {
            var old =
                    credentials.selectOne(
                            PrintSupport.<PrnCredentialVersion>owned(scope(a))
                                    .eq("credential_ref", p.getCredentialRef()));
            old.setStatus("QUERY_ONLY");
            old.setRetiredAt(now());
            old.setRevision(old.getRevision() + 1);
            credentials.updateById(old);
        }
        p.setCredentialRef(ref);
    }

    public PrinterDetailVO getPrinter(String no, PrintActor a) {
        var p = printer(no, a);
        var rt =
                runtimes.selectOne(
                        PrintSupport.<PrnPrinterRuntime>owned(scope(a)).eq("printer_no", no));
        return new PrinterDetailVO(
                vo(p),
                p.getConnectionRevision(),
                rt == null ? 0 : rt.getRevision(),
                p.getAgentNo(),
                p.getCredentialRef() != null,
                jobs.selectCount(
                        PrintSupport.<PrnJob>scoped(scope(a))
                                .eq("printer_no", no)
                                .in("delivery_stage", List.of("PENDING", "RETRY_WAIT"))),
                rt == null ? null : rt.getActiveJobNo(),
                rt == null ? null : rt.getBlockedReason());
    }

    private void syncCanonical(PrnPrinter p) {
        var device=canonicalDevices.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ai.neargo.shop.printing.entity.PrnDevice>()
                .eq("tenant_no",p.getTenantNo()).eq("device_no",p.getPrinterNo()).last("FOR UPDATE"));
        if(device==null) {
            device=new ai.neargo.shop.printing.entity.PrnDevice();
            device.setDeviceNo(p.getPrinterNo()); device.setTenantNo(p.getTenantNo());
            device.setEntityNo(p.getEntityNo()); device.setStoreNo(p.getStoreNo());
            device.setVersion(0L); device.setDeleted(0); device.setOnline(0);
            device.setAuthorizationVersion(0L); device.setActiveFenceEpoch(0L);
            device.setCreatedAt(now()); device.setUpdatedAt(now());
        }
        require(device.getEntityNo().equals(p.getEntityNo()) && device.getStoreNo().equals(p.getStoreNo()),"print.forbidden");
        boolean changed=!java.util.Objects.equals(device.getCredentialRef(),p.getCredentialRef())
                || !java.util.Objects.equals(device.getStatus(),p.getStatus());
        if(changed) device.setAuthorizationVersion(device.getAuthorizationVersion()+1);
        device.setVendor(p.getAdapterCode()); device.setConnectionType(p.getConnectionType());
        device.setCredentialRef(p.getCredentialRef()==null?"AGENT:"+p.getAgentNo():p.getCredentialRef());
        device.setBridgeActorNo(p.getAgentNo()==null?"CLOUD":p.getAgentNo());
        device.setStatus(p.getStatus()); device.setCapabilities(p.getCapabilities());
        device.setPhysicalKey(p.getPhysicalKey());
        if(device.getId()==null) canonicalDevices.insert(device); else canonicalDevices.updateById(device);
    }

    private void save(PrnPrinter p) {
        p.setRevision(p.getRevision() + 1);
        p.setUpdatedAt(now());
        syncCanonical(p);
        printers.updateById(p);
    }

    public PrintOperation updatePrinter(String no, UpdatePrinterReq r, PrintActor a) {
        return ops.execute(
                a,
                "UPDATE_PRINTER",
                json.freeze(Map.of("no", no, "request", r)).sha256(),
                () -> {
                    var p = printer(no, a);
                    revision(p, r.expectedRevision());
                    validate(r.name(), r.paperWidthMm());
                    require(
                            p.getPaperWidthMm().equals(r.paperWidthMm()),
                            "print.paperChangeRequiresNewPrinter");
                    p.setName(r.name());
                    save(p);
                    return succeeded(
                            "UPDATE_PRINTER", "PRINTER", no, json.freeze(Map.of("printerNo", no)));
                });
    }

    public PrintOperation changeConnection(String no, ChangeConnectionReq r, PrintActor a) {
        return ops.execute(
                a,
                "CHANGE_CONNECTION",
                json.freeze(Map.of("no", no, "request", r)).sha256(),
                () -> {
                    var p = printer(no, a);
                    revision(p, r.expectedRevision());
                    require(runtime(no, a).getActiveAttemptNo() == null, "print.printerUncertain");
                    configure(p, r.connection(), a);
                    p.setConnectionRevision(p.getConnectionRevision() + 1);
                    save(p);
                    return succeeded(
                            "CHANGE_CONNECTION",
                            "PRINTER",
                            no,
                            json.freeze(Map.of("printerNo", no)));
                });
    }

    public PrintOperation changeStatus(String no, ChangeStatusReq r, PrintActor a) {
        return ops.execute(
                a,
                "CHANGE_PRINTER_STATUS",
                json.freeze(Map.of("no", no, "request", r)).sha256(),
                () -> {
                    var p = printer(no, a);
                    revision(p, r.expectedRevision());
                    require(
                            Set.of("ACTIVE", "PAUSED", "RETIRED").contains(r.status()),
                            "print.invalidStatus");
                    if ("RETIRED".equals(r.status())) {
                        require(
                                runtime(no, a).getActiveAttemptNo() == null,
                                "print.printerUncertain");
                        for (var v :
                                routeVersions.selectList(
                                        PrintSupport.<PrnRouteVersion>owned(scope(a))
                                                .eq("status", "PUBLISHED")))
                            for (var rule :
                                    Arrays.asList(json.read(v.getRules(), RouteRule[].class)))
                                for (var target : rule.targets())
                                    require(
                                            !target.printerNo().equals(no),
                                            "print.printerReferenced");
                    }
                    p.setStatus(r.status());
                    save(p);
                    return succeeded(
                            "CHANGE_PRINTER_STATUS",
                            "PRINTER",
                            no,
                            json.freeze(Map.of("printerNo", no)));
                });
    }

    public PrintOperation rotateCredential(String no, RotateCredentialReq r, PrintActor a) {
        return ops.execute(
                a,
                "ROTATE_CREDENTIAL",
                json.freeze(Map.of("no", no, "request", r)).sha256(),
                () -> {
                    var p = printer(no, a);
                    revision(p, r.expectedRevision());
                    require("CLOUD".equals(p.getConnectionType()), "print.invalidConnection");
                    storeCredential(p, r.credentials(), a);
                    save(p);
                    return succeeded(
                            "ROTATE_CREDENTIAL",
                            "PRINTER",
                            no,
                            json.freeze(Map.of("printerNo", no)));
                });
    }

    public PrintOperation probe(String no, ProbeReq r, PrintActor a) {
        return ops.execute(
                a,
                "PROBE_PRINTER",
                json.freeze(Map.of("no", no, "request", r)).sha256(),
                () -> {
                    var p = printer(no, a);
                    revision(p, r.expectedRevision());
                    return new PrintOperation(
                            no("PO"),
                            "PROBE_PRINTER",
                            PrintOperationStatus.PENDING,
                            "PRINTER",
                            no,
                            json.freeze(
                                    Map.of(
                                            "printerNo",
                                            no,
                                            "commandType",
                                            "PROBE",
                                            "connectionRevision",
                                            p.getConnectionRevision(),
                                            "expiresAt",
                                            Instant.now().plusSeconds(60).toString())),
                            null,
                            Instant.now(),
                            null);
                });
    }

    public PrintOperation testPrint(String no, TestPrintReq r, PrintActor a) {
        return ops.execute(
                a,
                "TEST_PRINT",
                json.freeze(Map.of("no", no, "request", r)).sha256(),
                () -> {
                    var submission = testJobs.create(no, r.expectedRevision(), r.testPattern(), a);
                    return succeeded(
                            "TEST_PRINT",
                            "REQUEST",
                            submission.requestNo(),
                            json.freeze(submission));
                });
    }
}

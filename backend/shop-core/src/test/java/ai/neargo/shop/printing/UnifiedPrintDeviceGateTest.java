package ai.neargo.shop.printing;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.print.api.PrintScope;
import ai.neargo.shop.printing.center.audit.PrintActionAppender;
import ai.neargo.shop.printing.center.dispatch.impl.PrinterLeaseServiceImpl;
import ai.neargo.shop.printing.center.persistence.mapper.PrintMappers.PrnPrinterMapper;
import ai.neargo.shop.printing.center.persistence.mapper.PrintMappers.PrnPrinterRuntimeMapper;
import ai.neargo.shop.printing.center.persistence.mapper.PrintMappers.PrnRequestMapper;
import ai.neargo.shop.printing.mapper.PrintMappers.AttemptMapper;
import ai.neargo.shop.printing.mapper.PrintMappers.JobMapper;
import ai.neargo.shop.printing.service.PrintDeviceGate;
import ai.neargo.shop.spi.printing.PrintPort.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Both protocol entry points contend on the real canonical device row. */
class UnifiedPrintDeviceGateTest {
    private static final Scope OWNER = new Scope("E", "S", "OPERATOR");
    private static final Scope BRIDGE = new Scope("E", "S", "BRIDGE");
    private static final PrintScope SCOPE = new PrintScope("MAIN", "E", "S");
    private static final Document DOCUMENT = new Document(1, "ORDER", "BATCH", "订单", null,
            List.of(new Line("SKU", "1", null, null)), "不代表收款");
    private final PrintDeliveryTest fixture = new PrintDeliveryTest();
    private final PrnPrinterMapper printers = mock(PrnPrinterMapper.class);
    private final PrnPrinterRuntimeMapper runtimes = mock(PrnPrinterRuntimeMapper.class);
    private final JobMapper richJobs = mock(JobMapper.class);
    private final AttemptMapper richAttempts = mock(AttemptMapper.class);
    private final PrnRequestMapper requests = mock(PrnRequestMapper.class);
    private PrintDeviceGate gate;
    private PrinterLeaseServiceImpl center;

    @BeforeEach void setup() throws Exception {
        fixture.setup();
        fixture.service.heartbeat(BRIDGE, "DEVICE", true);
        gate = new PrintDeviceGate(fixture.devices, fixture.jobs);
        center = new PrinterLeaseServiceImpl(printers, runtimes, richJobs, richAttempts,
                requests, null, null, mock(PrintActionAppender.class));
        center.setDeviceGate(gate);
    }

    @Test void alwaysOnOwnerGovernanceAuditsTerminationBeforeLegacyReprintCanRun() {
        String job=enqueue("GOVERNANCE");
        var claim=fixture.service.claim(BRIDGE,"DEVICE");
        fixture.clock.advance(61);fixture.service.heartbeat(BRIDGE,"DEVICE",true);
        var reprint=fixture.service.reprint(OWNER,job,"queue checked","COPY-GOV");
        var version=gate.lock(SCOPE,"DEVICE").getVersion();
        var unsafe=new ai.neargo.shop.printing.service.PrintService.DeviceResolution("DEVICE",version,
                claim.attemptNo(),"operator inspected spool","EVIDENCE-1",false,"RESOLVE-1");
        assertThatThrownBy(()->fixture.service.resolveDeviceUncertainty(OWNER,unsafe)).isInstanceOf(BizException.class);
        assertThat(fixture.service.claim(BRIDGE,"DEVICE")).isNull();
        var safe=new ai.neargo.shop.printing.service.PrintService.DeviceResolution("DEVICE",version,
                claim.attemptNo(),"operator inspected spool","EVIDENCE-1",true,"RESOLVE-1");
        var resolved=fixture.service.resolveDeviceUncertainty(OWNER,safe);
        assertThat(fixture.service.get(OWNER,job).status()).isEqualTo("UNKNOWN");
        assertThat(gate.lock(SCOPE,"DEVICE").getExecutionResolutionHistory()).contains("EVIDENCE-1");
        assertThatThrownBy(()->fixture.service.acknowledge(BRIDGE,"DEVICE",new Acknowledge(
                claim.attemptNo(),claim.leaseToken(),"SUCCESS",null,"PRINTED"))).isInstanceOf(BizException.class);
        var next=fixture.service.claim(BRIDGE,"DEVICE");
        assertThat(next.jobNo()).isEqualTo(reprint.jobNo());
        assertThat(fixture.service.resolveDeviceUncertainty(OWNER,safe)).isEqualTo(resolved);
        assertThat(gate.lock(SCOPE,"DEVICE").getActiveAttemptNo()).isEqualTo(next.attemptNo());
    }

    @Test void legacyClaimPreventsRichPrepareBeforeAndAfterUnknownTimeout() {
        String job = enqueue("EVENT1");
        var claim = fixture.service.claim(BRIDGE, "DEVICE");
        assertThat(claim.jobNo()).isEqualTo(job);
        assertThat(center.prepare(SCOPE, "DEVICE", "WORKER")).isNull();
        fixture.clock.advance(61);
        assertThat(fixture.service.get(OWNER, job).status()).isEqualTo("UNKNOWN");
        fixture.service.heartbeat(BRIDGE, "DEVICE", true);
        assertThat(center.prepare(SCOPE, "DEVICE", "WORKER")).isNull();
        assertThat(fixture.service.claim(BRIDGE, "DEVICE")).isNull();
        assertThat(gate.lock(SCOPE, "DEVICE").getActiveAttemptNo()).isEqualTo(claim.attemptNo());
        verifyNoInteractions(printers, runtimes, richJobs, richAttempts, requests);
    }

    @Test void richAttemptPreventsLegacyClaimAndStaleReleaseCannotOpenTheGate() {
        String richJob = enqueue("RICH_EVENT");
        fixture.jdbc.update("UPDATE prn_job SET request_no='REQUEST',printer_no='DEVICE',"
                + "rich_document_snapshot='{}',delivery_stage='DISPATCHING',status='SENT',"
                + "current_attempt_no='RICH_AT' WHERE job_no=?", richJob);
        fixture.jdbc.update("INSERT INTO prn_attempt (tenant_no,entity_no,store_no,attempt_no,job_no,"
                + "sequence_no,status,started_at,lease_token,bridge_actor_no,created_at,updated_at,"
                + "printer_no,attempt_index,dispatch_key,request_hash)"
                + " VALUES ('MAIN','E','S','RICH_AT',?,1,'SUBMITTING',CURRENT_TIMESTAMP,"
                + "'TOKEN','WORKER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'DEVICE',1,'DISPATCH','HASH')", richJob);
        var device = gate.lock(SCOPE, "DEVICE");
        gate.occupy(device, richJob, "RICH_AT", device.getActiveFenceEpoch() + 1);
        String pending = enqueue("LEGACY_EVENT");
        assertThat(fixture.service.claim(BRIDGE, "DEVICE")).isNull();
        gate.release(SCOPE, "DEVICE", "OLD_AT");
        assertThat(gate.lock(SCOPE, "DEVICE").getActiveAttemptNo()).isEqualTo("RICH_AT");
        assertThat(fixture.service.claim(BRIDGE, "DEVICE")).isNull();
        fixture.jdbc.update("UPDATE prn_job SET status='UNKNOWN',delivery_stage='UNKNOWN' WHERE job_no=?", richJob);
        fixture.clock.advance(61);
        fixture.service.heartbeat(BRIDGE, "DEVICE", true);
        assertThat(fixture.service.claim(BRIDGE, "DEVICE")).isNull();
        assertThat(center.prepare(SCOPE, "DEVICE", "WORKER")).isNull();
        // Only an explicit resolution of the active attempt frees the shared queue.
        fixture.jdbc.update("UPDATE prn_job SET status='SUCCESS',delivery_stage='CONFIRMED' WHERE job_no=?", richJob);
        fixture.jdbc.update("UPDATE prn_attempt SET status='CONFIRMED' WHERE attempt_no='RICH_AT'");
        gate.release(SCOPE, "DEVICE", "RICH_AT");
        assertThat(fixture.service.claim(BRIDGE, "DEVICE").jobNo()).isEqualTo(pending);
        verifyNoInteractions(printers, runtimes, richJobs, richAttempts, requests);
    }

    @Test void lateLegacyTokenCannotReleaseANewerAttempt() {
        String job = enqueue("EVENT1");
        var first = fixture.service.claim(BRIDGE, "DEVICE");
        fixture.service.acknowledge(BRIDGE, "DEVICE", new Acknowledge(first.attemptNo(),
                first.leaseToken(), "FAILED", null, "NOT_PRINTED"));
        fixture.service.retry(OWNER, job);
        var current = fixture.service.claim(BRIDGE, "DEVICE");
        assertThatThrownBy(() -> fixture.service.acknowledge(BRIDGE, "DEVICE",
                new Acknowledge(first.attemptNo(), first.leaseToken(), "SUCCESS", null, "PRINTED")))
                .isInstanceOf(BizException.class);
        assertThat(gate.lock(SCOPE, "DEVICE").getActiveAttemptNo()).isEqualTo(current.attemptNo());
        assertThat(center.prepare(SCOPE, "DEVICE", "WORKER")).isNull();
    }

    @Test void rebindEpochRejectsTheOldBridgeReceiptAndRetainsUnknownGate() {
        String job = enqueue("EVENT1");
        var claim = fixture.service.claim(BRIDGE, "DEVICE");
        long epoch = gate.lock(SCOPE, "DEVICE").getAuthorizationVersion();
        fixture.service.revokeDevice(OWNER, "DEVICE", "bridge lost", "REVOKE1");
        fixture.service.bindDevice(OWNER,
                new DeviceReq("DEVICE", "GENERIC", "LAN", "VAULT:PRINT", "BRIDGE", "{}", true));
        fixture.service.heartbeat(BRIDGE, "DEVICE", true);
        assertThat(gate.lock(SCOPE, "DEVICE").getAuthorizationVersion()).isGreaterThan(epoch);
        assertThatThrownBy(() -> fixture.service.acknowledge(BRIDGE, "DEVICE",
                new Acknowledge(claim.attemptNo(), claim.leaseToken(), "SUCCESS", null, "PRINTED")))
                .isInstanceOf(BizException.class);
        assertThat(fixture.service.get(OWNER, job).status()).isEqualTo("UNKNOWN");
        assertThat(gate.lock(SCOPE, "DEVICE").getActiveAttemptNo()).isEqualTo(claim.attemptNo());
        assertThat(center.prepare(SCOPE, "DEVICE", "WORKER")).isNull();
    }

    @Test void eachHistoricalUnresolvedJobMustReleaseBeforeTheDeviceCanClaimAgain() {
        String unknownNo = enqueue("HIST_UNKNOWN_EVENT");
        String sentNo = enqueue("HIST_SENT_EVENT");
        String pendingNo = enqueue("NEW_PENDING_EVENT");
        var unknown = fixture.jobs.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<
                ai.neargo.shop.printing.entity.PrnJob>().eq("job_no", unknownNo));
        var sent = fixture.jobs.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<
                ai.neargo.shop.printing.entity.PrnJob>().eq("job_no", sentNo));
        // Recreate an upgraded database with more than one unresolved historical execution.
        unknown.setLegacyStatus("UNKNOWN");
        unknown.setCurrentAttemptNo("HIST_UNKNOWN_AT");
        fixture.jobs.updateById(unknown);
        sent.setLegacyStatus("SENT");
        sent.setCurrentAttemptNo("HIST_SENT_AT");
        sent.setLeaseUntil(java.time.LocalDateTime.ofInstant(fixture.clock.instant(),
                java.time.ZoneOffset.UTC).plusMinutes(10));
        fixture.jobs.updateById(sent);
        var migratedDevice = gate.lock(SCOPE, "DEVICE");
        migratedDevice.setActiveJobNo(unknownNo);
        migratedDevice.setActiveAttemptNo("HIST_UNKNOWN_AT");
        fixture.devices.updateById(migratedDevice);

        assertThat(fixture.service.claim(BRIDGE, "DEVICE")).isNull();
        assertThat(center.prepare(SCOPE, "DEVICE", "WORKER")).isNull();
        gate.release(SCOPE, "DEVICE", "HIST_UNKNOWN_AT");
        assertThat(fixture.jobs.selectById(unknown.getId()).getExecutionReleasedAt()).isNotNull();
        assertThat(fixture.jobs.selectById(sent.getId()).getExecutionReleasedAt()).isNull();
        assertThat(gate.lock(SCOPE, "DEVICE").getActiveJobNo()).isEqualTo(sentNo);
        assertThat(gate.lock(SCOPE, "DEVICE").getActiveAttemptNo()).isEqualTo("HIST_SENT_AT");
        assertThat(fixture.service.claim(BRIDGE, "DEVICE")).isNull();
        assertThat(center.prepare(SCOPE, "DEVICE", "WORKER")).isNull();

        // Replaying the first release must leave the next historical execution fenced.
        gate.release(SCOPE, "DEVICE", "HIST_UNKNOWN_AT");
        assertThat(fixture.jobs.selectById(sent.getId()).getExecutionReleasedAt()).isNull();
        assertThat(gate.lock(SCOPE, "DEVICE").getActiveAttemptNo()).isEqualTo("HIST_SENT_AT");
        gate.release(SCOPE, "DEVICE", "HIST_SENT_AT");
        assertThat(fixture.jobs.selectById(sent.getId()).getExecutionReleasedAt()).isNotNull();
        assertThat(gate.lock(SCOPE, "DEVICE").getActiveAttemptNo()).isNull();
        assertThat(fixture.service.claim(BRIDGE, "DEVICE").jobNo()).isEqualTo(pendingNo);
        verifyNoInteractions(printers, runtimes, richJobs, richAttempts, requests);
    }

    @Test void canonicalGateKeepsSixtyFourCharacterOwnerIdentityAcrossBothEntrypoints() {
        String entity = "E".repeat(64), store = "S".repeat(64);
        for (String table : List.of("prn_device", "prn_template", "prn_route", "prn_job", "prn_attempt"))
            fixture.jdbc.update("UPDATE " + table + " SET entity_no=?,store_no=?", entity, store);
        var owner = new Scope(entity, store, "OPERATOR");
        var bridge = new Scope(entity, store, "BRIDGE");
        var scope = new PrintScope("MAIN", entity, store);
        fixture.service.heartbeat(bridge, "DEVICE", true);
        String job = fixture.service.enqueue(owner, new Enqueue("LONG_OWNER", null, "PART", DOCUMENT))
                .getFirst().jobNo();
        var claimed = fixture.service.claim(bridge, "DEVICE");
        assertThat(claimed.jobNo()).isEqualTo(job);
        assertThat(gate.lock(scope, "DEVICE").getEntityNo()).isEqualTo(entity);
        assertThat(gate.lock(scope, "DEVICE").getStoreNo()).isEqualTo(store);
        assertThat(center.prepare(scope, "DEVICE", "WORKER")).isNull();
        fixture.clock.advance(61);
        fixture.service.heartbeat(bridge, "DEVICE", true);
        assertThat(fixture.service.get(owner, job).status()).isEqualTo("UNKNOWN");
        assertThat(center.prepare(scope, "DEVICE", "WORKER")).isNull();
        assertThat(gate.lock(scope, "DEVICE").getActiveAttemptNo()).isEqualTo(claimed.attemptNo());
        verifyNoInteractions(printers, runtimes, richJobs, richAttempts, requests);
    }

    private String enqueue(String event) {
        return fixture.service.enqueue(OWNER, new Enqueue(event, null, "PART", DOCUMENT)).getFirst().jobNo();
    }
}

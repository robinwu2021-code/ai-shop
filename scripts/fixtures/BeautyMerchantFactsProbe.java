import ai.neargo.shop.beauty.server.BeautyApplication;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.personnel.PersonnelFactsPort;
import ai.neargo.shop.spi.resource.ResourceFactsPort;
import ai.neargo.shop.spi.schedule.ScheduleWindowsPort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Set;
import org.springframework.boot.SpringApplication;
import tools.jackson.databind.ObjectMapper;

/** Test-only invoker over the extracted, hashed real server artifact; it adds no route or provider override. */
public class BeautyMerchantFactsProbe {
    public static void main(String[] args) throws Exception {
        try (var context = SpringApplication.run(BeautyApplication.class, args)) {
            var env = context.getEnvironment();
            String entity = env.getRequiredProperty("bm02.probe.entity");
            String store = env.getRequiredProperty("bm02.probe.store");
            String person = env.getRequiredProperty("bm02.probe.personnel");
            String staff = env.getRequiredProperty("bm02.probe.resource");
            Instant at = Instant.parse("2026-10-22T10:30:00Z");
            var people = context.getBean(PersonnelFactsPort.class);
            var resources = context.getBean(ResourceFactsPort.class);
            var schedules = context.getBean(ScheduleWindowsPort.class);
            if (!people.getClass().getSimpleName().equals("RemotePersonnelFactsPort")
                    || !resources.getClass().getSimpleName().equals("RemoteResourceFactsPort")
                    || !schedules.getClass().getSimpleName().equals("RemoteScheduleWindowsPort")) {
                throw new IllegalStateException("Standalone must use all three actual remote adapters");
            }
            var evidence = new LinkedHashMap<String, Object>();
            boolean failureExpected = env.getProperty("bm02.probe.failure", Boolean.class, false);
            if (env.getProperty("bm02.probe.conflict", Boolean.class, false)) {
                boolean conflicted = false;
                try { schedules.getBatch(new ScheduleWindowsPort.Query("MAIN", entity, store, Set.of(staff),
                        at.minusSeconds(3600), at.plusSeconds(3600))); }
                catch (BizException conflict) { conflicted = conflict.errorCode() == ErrorCode.CONFLICT; }
                if (!conflicted) { throw new IllegalStateException("Actual provider conflict lost its status"); }
                evidence.put("ownerConflict", true);
            } else if (failureExpected) {
                int failed = 0;
                try { people.getBatch(new PersonnelFactsPort.Query("MAIN", entity, store, Set.of(person), at)); }
                catch (RuntimeException failure) { failed++; }
                try { resources.getBatch(new ResourceFactsPort.Query("MAIN", entity, store, Set.of(staff), at)); }
                catch (RuntimeException failure) { failed++; }
                try { schedules.getBatch(new ScheduleWindowsPort.Query("MAIN", entity, store, Set.of(staff),
                        at.minusSeconds(3600), at.plusSeconds(3600))); }
                catch (RuntimeException failure) { failed++; }
                if (failed != 3) { throw new IllegalStateException("Owner outage must fail all facts closed"); }
                evidence.put("failedClosed", failed);
            } else {
                var personnel = people.getBatch(
                        new PersonnelFactsPort.Query("MAIN", entity, store, Set.of(person), at));
                var resource = resources.getBatch(
                        new ResourceFactsPort.Query("MAIN", entity, store, Set.of(staff), at));
                var windows = schedules.getBatch(new ScheduleWindowsPort.Query("MAIN", entity, store, Set.of(staff),
                        at.minusSeconds(3600), at.plusSeconds(3600)));
                if (personnel.size() != 1 || personnel.getFirst().qualifications().isEmpty()
                        || resource.size() != 1 || resource.getFirst().capacity() != 1 || windows.size() != 1) {
                    throw new IllegalStateException("Actual scoped owner facts were missing or duplicated");
                }
                evidence.put("personnel", personnel);
                evidence.put("resources", resource);
                evidence.put("windows", windows);
            }
            Files.writeString(Path.of(env.getRequiredProperty("bm02.probe.output")),
                    context.getBean(ObjectMapper.class).writeValueAsString(evidence));
        }
    }
}

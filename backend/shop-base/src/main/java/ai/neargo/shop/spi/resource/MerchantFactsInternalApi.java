package ai.neargo.shop.spi.resource;

import ai.neargo.shop.spi.personnel.PersonnelFactsPort;
import ai.neargo.shop.spi.schedule.ScheduleWindowsPort;
import java.util.List;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Credentialed industry services read shared owner facts; no public directory or capacity permission is exposed. */
public interface MerchantFactsInternalApi {
    String PERSONNEL_PATH = "/internal/merchant/facts/personnel";
    String RESOURCE_PATH = "/internal/merchant/facts/resources";
    String SCHEDULE_PATH = "/internal/merchant/facts/schedules";
    @PostExchange(PERSONNEL_PATH)
    List<PersonnelFactsPort.PersonnelFact> personnel(@RequestBody PersonnelFactsPort.Query query);
    @PostExchange(RESOURCE_PATH)
    List<ResourceFactsPort.ResourceFact> resources(@RequestBody ResourceFactsPort.Query query);
    @PostExchange(SCHEDULE_PATH)
    List<ScheduleWindowsPort.Window> schedules(@RequestBody ScheduleWindowsPort.Query query);
}

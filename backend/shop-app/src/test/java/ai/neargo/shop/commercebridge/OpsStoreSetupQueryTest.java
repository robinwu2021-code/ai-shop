package ai.neargo.shop.commercebridge;

import ai.neargo.shop.spi.user.MerchantQueryPort.IndustryRow;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OpsStoreSetupQueryTest {
    private IndustryRow row(String industry,String sub,String business,boolean enabled) {
        return new IndustryRow(industry,sub,business,industry,industry,industry,10,enabled);
    }
    @Test void showsServiceDictionaryWithoutPretendingAnUnpublishedBusinessTypeIsAvailable() {
        var choices=OpsStoreSetupQuery.choices(List.of(row("RETAIL",null,"RETAIL",true),
                row("RETAIL","SHOP","RETAIL",true),row("SERVICE",null,"SERVICE",true),
                row("SERVICE","HAIR","SERVICE",true)),Set.of("RETAIL"));
        assertEquals(4,choices.size());
        assertTrue(choices.stream().filter(c->c.industryCode().equals("RETAIL")).allMatch(OpsStoreSetupQuery.Choice::available));
        assertTrue(choices.stream().filter(c->c.industryCode().equals("SERVICE")).noneMatch(OpsStoreSetupQuery.Choice::available));
    }
    @Test void publicationMakesServiceSelectableAndDisabledParentsRemainHidden() {
        var choices=OpsStoreSetupQuery.choices(List.of(row("SERVICE",null,"SERVICE",true),
                row("SERVICE","HAIR","SERVICE",true),row("OFF",null,"SERVICE",false),
                row("OFF","CHILD","SERVICE",true)),Set.of("SERVICE"));
        assertEquals(2,choices.size());assertTrue(choices.stream().allMatch(OpsStoreSetupQuery.Choice::available));
    }
}

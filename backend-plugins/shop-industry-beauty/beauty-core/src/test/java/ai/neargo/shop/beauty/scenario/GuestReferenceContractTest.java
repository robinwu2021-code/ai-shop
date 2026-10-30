package ai.neargo.shop.beauty.scenario;

import ai.neargo.shop.beauty.dto.PreviewAppointmentReq;
import ai.neargo.shop.beauty.dto.CreateAppointmentReq;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** Guest identity must survive the public DTO boundary without rewriting old anonymous requests. */
class GuestReferenceContractTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void explicitGuestSurvivesPreviewAndCreate() {
        var preview = json.readValue("""
                {"storeNo":"S","guestNo":"GUE1","items":[{"skuNo":"SKU","qty":1}],
                 "startAt":"2026-12-01T10:00:00Z"}
                """, PreviewAppointmentReq.class);
        assertThat(json.readTree(json.writeValueAsString(preview)).path("guestNo").asString()).isEqualTo("GUE1");
        var create = json.readValue("{\"previewNo\":\"P\",\"guestNo\":\"GUE1\"}", CreateAppointmentReq.class);
        assertThat(json.readTree(json.writeValueAsString(create)).path("guestNo").asString()).isEqualTo("GUE1");
    }
}

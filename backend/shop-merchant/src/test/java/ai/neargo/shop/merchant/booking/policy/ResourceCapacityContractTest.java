package ai.neargo.shop.merchant.policy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import ai.neargo.shop.spi.resource.ResourceFactsPort.ResourceFact;
import ai.neargo.shop.spi.resource.ResourceCandidatesPort.Capacity;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Old remote shape must fail closed rather than default actual evidence to zero. */
class ResourceCapacityContractTest {
    @Test void legacyRemoteFactsWithoutActualEvidenceCannotDecodeAsAvailable() {
        String valid = "{\"resourceNo\":\"R\",\"resourceType\":\"ROOM\",\"storeNo\":\"S\","
                + "\"personnelNo\":null,\"name\":\"Room\",\"capacity\":3,\"openOccupied\":false,\"actualUsed\":1}";
        assertThat(new JsonMapper().readValue(valid, ResourceFact.class).actualUsed()).isEqualTo(1L);
        assertThatThrownBy(() -> new JsonMapper().readValue(
                "{\"resourceNo\":\"R\",\"resourceType\":\"ROOM\",\"storeNo\":\"S\","
                + "\"personnelNo\":null,\"name\":\"Room\",\"capacity\":3,\"openOccupied\":false}", ResourceFact.class))
                .isInstanceOf(RuntimeException.class).hasMessageContaining("actual capacity observation");
    }
    @Test void legacyCapacityReplyWithoutProjectionVersionCannotDecodeAsAvailable() {
        String valid = "{\"resourceNo\":\"R\",\"from\":\"2026-10-29T00:00:00Z\","
                + "\"to\":\"2026-10-29T00:05:00Z\",\"capacity\":3,\"used\":1,\"projectionSchema\":\"RESOURCE_CAPACITY_V2\"}";
        assertThat(new JsonMapper().readValue(valid, Capacity.class).used()).isEqualTo(1);
        assertThatThrownBy(() -> new JsonMapper().readValue(
                "{\"resourceNo\":\"R\",\"from\":\"2026-10-29T00:00:00Z\","
                + "\"to\":\"2026-10-29T00:05:00Z\",\"capacity\":3,\"used\":0}", Capacity.class))
                .isInstanceOf(RuntimeException.class).hasMessageContaining("complete actual capacity projection");
    }
}

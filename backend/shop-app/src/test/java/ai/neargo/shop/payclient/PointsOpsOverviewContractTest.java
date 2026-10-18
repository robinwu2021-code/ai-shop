package ai.neargo.shop.payclient;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.portal.ops.pay.OpsPointsController;
import ai.neargo.shop.portal.ops.pay.OpsPointsPolicyController;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

class PointsOpsOverviewContractTest {
    @Test
    void policy_controller_replaces_the_legacy_setting_service_with_versioned_configuration() {
        assertThat(
                        Arrays.stream(OpsPointsPolicyController.class.getDeclaredFields())
                                .map(f -> f.getType().getSimpleName())
                                .toList())
                .doesNotContain("PointsPolicyAppService")
                .contains("PointsConfigurationAppService");
    }

    @Test
    void overview_uses_the_new_four_pool_contract_instead_of_the_legacy_service() throws Exception {
        assertThat(
                        Arrays.stream(OpsPointsController.class.getDeclaredFields())
                                .map(f -> f.getType().getSimpleName())
                                .toList())
                .doesNotContain("PointsService")
                .contains("PointsOpsAppService");
        Class<?> response =
                OpsPointsController.class
                        .getMethod("overview", String.class, String.class)
                        .getReturnType();
        Set<String> fields =
                Arrays.stream(response.getRecordComponents())
                        .map(c -> c.getName())
                        .collect(Collectors.toSet());
        assertThat(fields)
                .contains(
                        "typeCode",
                        "currency",
                        "ratePoints",
                        "rateMinor",
                        "uMinor",
                        "cMinor",
                        "dMinor",
                        "bMinor",
                        "reservedBMinor",
                        "lockedUMinor",
                        "lockedBMinor",
                        "receivableMinor");
        assertThat(fields).doesNotContain("payloadJson", "channelBalances");
    }
}

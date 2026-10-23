package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.common.ApiResponseWrapper;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.invbridge.port.InventoryReservationPortImpl;
import ai.neargo.shop.inventory.api.internal.InternalInventoryReservationController;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.inventory.service.ReservationService;
import ai.neargo.shop.spi.inventory.InventoryReservationInternalApi;
import ai.neargo.shop.spi.inventory.InventoryReservationPort;
import ai.neargo.shop.svc.InternalHttp;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class InventoryReservationTransportTest {
    private static final InventoryReservationPort.Scope SCOPE =
            new InventoryReservationPort.Scope("MAIN", "ENTITY", "ORDER");
    private static final String BODY = """
            {"scope":{"tenantNo":"MAIN","entityNo":"ENTITY","externalRef":"ORDER"},
             "lines":[{"skuNo":"SKU","storeNo":"STORE","qty":2,"fulfillmentRef":"SUB"}],
             "ttlSeconds":900,"lifecycleVersion":3}
            """;

    private MockMvc mvc(InventoryReservationPort port) {
        var beans = new DefaultListableBeanFactory();
        if (port != null) beans.registerSingleton("inventoryReservations", port);
        ObjectProvider<InventoryReservationPort> provider = beans.getBeanProvider(InventoryReservationPort.class);
        return MockMvcBuilders.standaloneSetup(new InternalInventoryReservationController(provider,
                "secret", JsonMapper.builder().build()))
                .setControllerAdvice(new GlobalExceptionHandler(), new ApiResponseWrapper()).build();
    }

    @Test void authenticatesBeforeStrictParsingAndRejectsFractionalOrUnknownSourceFields() throws Exception {
        InventoryReservationPort port = mock(InventoryReservationPort.class);
        var mvc = mvc(port);
        for (String body : List.of(BODY, "{", "null", "")) {
            mvc.perform(post(InventoryReservationInternalApi.ROOT + "/reserve")
                    .contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        }
        for (String body : List.of("{}", "null", BODY.replace("\"qty\":2", "\"qty\":2.5"),
                BODY.replace("\"qty\":2", "\"qty\":\"2\""), BODY.replace("\"qty\":2", "\"qty\":0"),
                BODY.replace("\"qty\":2", "\"qty\":2147483648"),
                BODY.replace("\"qty\":2", "\"qty\":2,\"warehouseId\":\"FORGED\""),
                BODY.replace("\"ttlSeconds\":900", "\"ttlSeconds\":900.5"),
                BODY.replace("\"entityNo\":\"ENTITY\"", "\"entityNo\":123"))) {
            mvc.perform(post(InventoryReservationInternalApi.ROOT + "/reserve")
                    .header(InternalHttp.TOKEN_HEADER, "secret").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(port);
    }

    @Test void unavailableAuthorityReturns503AndQueryAbsenceHasRawTypedState() throws Exception {
        String body = "{\"scope\":{\"tenantNo\":\"MAIN\",\"entityNo\":\"ENTITY\",\"externalRef\":\"ORDER\"}}";
        mvc(null).perform(post(InventoryReservationInternalApi.ROOT + "/query")
                .header(InternalHttp.TOKEN_HEADER, "secret").contentType("application/json").content(body))
                .andExpect(status().isServiceUnavailable());
        InventoryReservationPort port = mock(InventoryReservationPort.class);
        when(port.query(SCOPE)).thenReturn(new InventoryReservationPort.Snapshot(SCOPE, null,
                InventoryReservationPort.State.ABSENT, 0, false, List.of()));
        mvc(port).perform(post(InventoryReservationInternalApi.ROOT + "/query")
                .header(InternalHttp.TOKEN_HEADER, "secret").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ABSENT"))
                .andExpect(jsonPath("$.scope.externalRef").value("ORDER"));
    }

    @Test void localAdapterUsesExactExistingOwnerReferenceAndNeverAcceptsForeignSkuOrUnknownStore() {
        InventoryAclService acl = mock(InventoryAclService.class);
        ReservationService reservations = mock(ReservationService.class);
        var port = new InventoryReservationPortImpl(acl, reservations);
        var line = new InventoryReservationPort.Line("SKU", "STORE", 2, "SUB");
        when(acl.findOwnerId("ENTITY")).thenReturn("OWNER");
        when(acl.ownerOfSku("SKU")).thenReturn("OTHER_OWNER");
        assertThatThrownBy(() -> port.reserve(SCOPE, List.of(line), 900, 3))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        when(acl.ownerOfSku("SKU")).thenReturn("OWNER");
        when(acl.itemIdOfSku("SKU")).thenReturn("ITEM");
        assertThatThrownBy(() -> port.reserve(SCOPE, List.of(line), 900, 3))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(reservations, never()).reserve(any(), any(), anyList(), anyLong(), anyInt());
        when(acl.stockLocationOfExistingStore("ENTITY", "STORE")).thenReturn("ACTUAL_SOURCE");
        when(acl.skuOfItem("OWNER", "ITEM")).thenReturn("SKU");
        when(reservations.query("OWNER", "ORDER")).thenReturn(Optional.of(new ReservationService.Snapshot(
                "RESERVATION", ReservationService.Status.HELD, 3, false,
                List.of(new ReservationService.Line("ITEM", "ACTUAL_SOURCE", 2, "SUB")))));
        var result = port.reserve(SCOPE, List.of(line), 900, 3);
        verify(reservations).reserve(eq("OWNER"), eq("ORDER"), eq(List.of(
                new ReservationService.Line("ITEM", "ACTUAL_SOURCE", 2, "SUB"))), eq(900L), eq(3));
        assertThat(result.originalLines()).containsExactly(new InventoryReservationPort.ReservedLine("SKU", 2, "SUB"));
        verify(acl, never()).ownerIdOf(any());
        verify(acl, never()).locationOfStore(any(), any());
    }
}

package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.CreateOptionGroupReq;
import ai.neargo.shop.food.dto.ReplaceOptionGroupItemsReq;
import ai.neargo.shop.food.dto.OptionGroupMutationVO;
import ai.neargo.shop.food.dto.SelectionRules;
import ai.neargo.shop.food.dto.CreateOptionGroupResultVO;
import ai.neargo.shop.food.dto.ListOptionGroupsQuery;
import ai.neargo.shop.food.dto.OptionGroupConfigVO;
import ai.neargo.shop.food.dto.OptionGroupResultVO;
import ai.neargo.shop.food.foodclient.RemoteOptionGroupService;
import ai.neargo.shop.food.spi.OptionGroupInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RemoteOptionGroupServiceTest {
    private static CreateOptionGroupReq req() {
        return new CreateOptionGroupReq("糖度", BigDecimal.ZERO, BigDecimal.ONE);
    }
    private static OptionGroupConfigVO group(String number) {
        return new OptionGroupConfigVO(number, "OPTION_GROUP", 0, "DRAFT",
                new OptionGroupConfigVO.Data("糖度", BigDecimal.ZERO, BigDecimal.ONE, null, Map.of(), List.of()));
    }

    @Test void originalTypedTransportPreservesInputsAndRawSuccessForAllThreeOperations() throws Exception {
        var count = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/food/option-groups/", ex -> {
            count.incrementAndGet();
            assertThat(ex.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("wire");
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(body).contains("\"entityNo\":\"E\"", "\"storeNo\":\"A\"");
            String group = """
                    {"configNo":"OG","kind":"OPTION_GROUP","revision":0,"status":"DRAFT",
                     "data":{"name":"糖度","minQty":0,"maxQty":1,"selectionRules":{},"items":[]}}
                    """;
            String response = switch (ex.getRequestURI().getPath()) {
                case OptionGroupInternalApi.CREATE_PATH -> {
                    assertThat(body).contains("\"actorNo\":\"trusted\"", "\"idempotencyKey\":\"stable\"");
                    yield "{\"configNo\":\"OG\",\"version\":0,\"status\":\"DRAFT\"}";
                }
                case OptionGroupInternalApi.GET_PATH -> "{\"config\":" + group + "}";
                default -> "{\"records\":[" + group + "],\"total\":1,\"page\":1,\"size\":20}";
            };
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes); ex.close();
        });
        server.start();
        try {
            var service = remote(server);
            assertThat(service.createOptionGroup("E", "A", "trusted", req(), "stable").configNo()).isEqualTo("OG");
            assertThat(service.getOptionGroup("E", "A", "OG").config().data().name()).isEqualTo("糖度");
            assertThat(service.listOptionGroups("E", "A", null).total()).isOne();
            assertThat(count).hasValue(3);
        } finally { server.stop(0); }
    }

    @Test void translatesBusinessFailuresAndNeverRetriesUncertainWrites() throws Exception {
        for (int code : List.of(400, 403, 404, 409, 503)) {
            var count = new AtomicInteger();
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(OptionGroupInternalApi.CREATE_PATH, ex -> {
                count.incrementAndGet(); ex.sendResponseHeaders(code, -1); ex.close();
            });
            server.start();
            try {
                var failure = catchThrowable(() ->
                        remote(server).createOptionGroup("E", "A", "trusted", req(), "stable"));
                if (code < 500) {
                    assertThat(failure).isInstanceOfSatisfying(BizException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(switch (code) {
                                case 400 -> ErrorCode.BAD_REQUEST;
                                case 403 -> ErrorCode.FORBIDDEN;
                                case 404 -> ErrorCode.NOT_FOUND;
                                default -> ErrorCode.CONFLICT;
                            }));
                } else assertThat(failure).isNotNull().isNotInstanceOf(BizException.class);
                assertThat(count).hasValue(1);
            } finally { server.stop(0); }
        }
    }

    @Test void rejectsMalformedCreateAndWrongDetailIdentityReceipts() {
        var api = mock(OptionGroupInternalApi.class);
        var service = new RemoteOptionGroupService(api);
        for (var receipt : List.of(new CreateOptionGroupResultVO("", 0, "DRAFT"),
                new CreateOptionGroupResultVO("OG", -1, "DRAFT"),
                new CreateOptionGroupResultVO("OG", 0, "ACTIVE"))) {
            when(api.createOptionGroup(any())).thenReturn(receipt);
            assertThatThrownBy(() -> service.createOptionGroup("E", "A", "actor", req(), "key"))
                    .isInstanceOf(IllegalStateException.class);
        }
        when(api.getOptionGroup(any())).thenReturn(new OptionGroupResultVO(group("OTHER")));
        assertThatThrownBy(() -> service.getOptionGroup("E", "A", "OG")).isInstanceOf(IllegalStateException.class);
        when(api.getOptionGroup(any())).thenReturn(new OptionGroupResultVO(null));
        assertThatThrownBy(() -> service.getOptionGroup("E", "A", "OG")).isInstanceOf(IllegalStateException.class);
    }

    @Test void rejectsDuplicateGroupsWrongPageAndInvalidDefinitionsInQueryReceipts() {
        var api = mock(OptionGroupInternalApi.class);
        var service = new RemoteOptionGroupService(api);
        var invalid = new OptionGroupConfigVO("OG", "MODIFIER", 0, "DRAFT", group("OG").data());
        for (var page : List.of(PageData.of(List.of(group("OG"), group("OG")), 2, 1, 20),
                PageData.of(List.of(group("OG")), 1, 2, 20), PageData.of(List.of(invalid), 1, 1, 20),
                PageData.of(List.of(group("OG")), 0, 1, 20))) {
            when(api.listOptionGroups(any())).thenReturn(page);
            assertThatThrownBy(() -> service.listOptionGroups("E", "A", new ListOptionGroupsQuery(1, 20)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void replaceUsesTypedTrustedCommandAndValidatesExpectedNextVersion() {
        var api = mock(OptionGroupInternalApi.class);
        var request = new ReplaceOptionGroupItemsReq(2L, List.of(), List.of(),
                new SelectionRules(List.of(), List.of()));
        when(api.replaceOptionGroupItems(any())).thenAnswer(invocation -> {
            OptionGroupInternalApi.ReplaceCommand c = invocation.getArgument(0);
            assertThat(c.entityNo()).isEqualTo("E"); assertThat(c.storeNo()).isEqualTo("A");
            assertThat(c.actorNo()).isEqualTo("actor"); assertThat(c.groupNo()).isEqualTo("OG");
            assertThat(c.idempotencyKey()).isEqualTo("stable");
            return new OptionGroupMutationVO("OG", 3);
        });
        var service = new RemoteOptionGroupService(api);
        assertThat(service.replaceOptionGroupItems("E", "A", "OG", "actor", request, "stable").version()).isEqualTo(3);
        doReturn(new OptionGroupMutationVO("OG", 2)).when(api).replaceOptionGroupItems(any());
        assertThatThrownBy(() -> service.replaceOptionGroupItems("E", "A", "OG", "actor", request, "stable"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static RemoteOptionGroupService remote(HttpServer server) {
        var http = new InternalHttp(name -> Optional.of("http://127.0.0.1:" + server.getAddress().getPort()), "wire");
        return new RemoteOptionGroupService(http.client(ServiceName.FOOD, OptionGroupInternalApi.class,
                Duration.ofSeconds(2)));
    }
}

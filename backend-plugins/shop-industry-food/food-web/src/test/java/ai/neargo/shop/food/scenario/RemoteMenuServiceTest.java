package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.food.dto.CreateMenuReq;
import ai.neargo.shop.food.dto.CreateMenuResultVO;
import ai.neargo.shop.food.dto.ListMenusQuery;
import ai.neargo.shop.food.dto.MenuConfigResultVO;
import ai.neargo.shop.food.dto.MenuResultVO;
import ai.neargo.shop.food.dto.MenuVO;
import ai.neargo.shop.food.dto.UpdateMenuReq;
import ai.neargo.shop.food.foodclient.RemoteMenuService;
import ai.neargo.shop.food.spi.MenuInternalApi;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RemoteMenuServiceTest {
    static MenuVO view(String store, String name, long version) {
        return new MenuVO("M1", store, name, "TIME", "Asia/Shanghai", 10, null, "DRAFT", version, version);
    }
    @Test void creationMutationAndDetailRejectWrongIdentityOrFieldsAndAcceptValidReceipts() {
        var api = mock(MenuInternalApi.class); var s = new RemoteMenuService(api);
        var req = new CreateMenuReq("早餐", "TIME", "Asia/Shanghai", 10, null);
        when(api.createMenu(any())).thenReturn(new CreateMenuResultVO("M1", 0L, "DRAFT"));
        assertThat(s.createMenu("E", "A", "actor", req, "key").configNo()).isEqualTo("M1");
        when(api.createMenu(any())).thenReturn(new CreateMenuResultVO("M1", null, "DRAFT"));
        assertThatThrownBy(() -> s.createMenu("E", "A", "actor", req, "key"))
                .isInstanceOf(IllegalStateException.class);
        var update = new UpdateMenuReq("早餐", "TIME", "Asia/Shanghai", 10, null, 0L);
        when(api.updateMenu(any())).thenReturn(new MenuResultVO(view("A", "晚餐", 1)));
        assertThatThrownBy(() -> s.updateMenu("E", "A", "M1", "actor", update, "edit"))
                .isInstanceOf(IllegalStateException.class);
        when(api.updateMenu(any())).thenReturn(new MenuResultVO(view("A", "早餐", 1)));
        assertThat(s.updateMenu("E", "A", "M1", "actor", update, "edit").menu().version()).isEqualTo(1);
        when(api.getMenu(any())).thenReturn(new MenuConfigResultVO(view("B", "早餐", 0)));
        assertThatThrownBy(() -> s.getMenu("E", "A", "M1")).isInstanceOf(IllegalStateException.class);
        when(api.getMenu(any())).thenReturn(new MenuConfigResultVO(view("A", "早餐", 0)));
        assertThat(s.getMenu("E", "A", "M1").config().storeNo()).isEqualTo("A");
    }
    @Test void pagesRequireCompleteStrictCountersBoundedUniqueRecordsAndRequestedStatus() {
        var api = mock(MenuInternalApi.class); var s = new RemoteMenuService(api);
        var q = new ListMenusQuery(1, 20, "DRAFT", null);
        when(api.listMenus(any())).thenReturn(new MenuInternalApi.PageReceipt(List.of(view("A", "早餐", 0)),
                1L, 1L, 20L));
        assertThat(s.listMenus("E", "A", q).total()).isEqualTo(1);
        for (var bad : List.of(new MenuInternalApi.PageReceipt(List.of(view("B", "早餐", 0)), 1L, 1L, 20L),
                new MenuInternalApi.PageReceipt(List.of(), null, 1L, 20L),
                new MenuInternalApi.PageReceipt(List.of(view("A", "早餐", 0), view("A", "早餐", 0)), 2L, 1L, 20L))) {
            when(api.listMenus(any())).thenReturn(bad);
            assertThatThrownBy(() -> s.listMenus("E", "A", q)).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void pageCardinalityMustAgreeWithTotalAndOffsetIncludingEmptyPastLastPage() {
        var api = mock(MenuInternalApi.class); var service = new RemoteMenuService(api);
        for (var bad : List.of(new MenuInternalApi.PageReceipt(List.of(), 100L, 1L, 20L),
                new MenuInternalApi.PageReceipt(List.of(view("A", "早餐", 0)), 1L, 2L, 20L),
                new MenuInternalApi.PageReceipt(List.of(view("A", "早餐", 0)), 20L, 1L, 20L))) {
            when(api.listMenus(any())).thenReturn(bad);
            var query = new ListMenusQuery(bad.page().intValue(), 20, null, null);
            assertThatThrownBy(() -> service.listMenus("E", "A", query)).isInstanceOf(IllegalStateException.class);
        }
        when(api.listMenus(any())).thenReturn(new MenuInternalApi.PageReceipt(List.of(view("A", "早餐", 0)),
                21L, 2L, 20L));
        assertThat(service.listMenus("E", "A", new ListMenusQuery(2, 20, null, null)).records()).hasSize(1);
        when(api.listMenus(any())).thenReturn(new MenuInternalApi.PageReceipt(List.of(), 1L, 2L, 20L));
        assertThat(service.listMenus("E", "A", new ListMenusQuery(2, 20, null, null)).records()).isEmpty();
        when(api.listMenus(any())).thenReturn(new MenuInternalApi.PageReceipt(List.of(), 0L,
                (long) Integer.MAX_VALUE, 100L));
        assertThat(service.listMenus("E", "A", new ListMenusQuery(Integer.MAX_VALUE, 100, null, null)).records())
                .isEmpty();
    }

    @Test void jsonDoesNotCoerceTextVersionsPriorityOrPaginationAndMissingMetadataIsRejected() {
        var json = new ObjectMapper();
        String command = "{\"entityNo\":\"E\",\"storeNo\":\"A\",\"actorNo\":\"actor\","
                + "\"idempotencyKey\":\"key\",\"definition\":{\"name\":\"早餐\",\"menuType\":\"TIME\","
                + "\"timezone\":\"Asia/Shanghai\",\"priority\":1}}";
        assertThat(json.readValue(command, MenuInternalApi.CreateCommand.class).entityNo()).isEqualTo("E");
        for (String bad : List.of(command.replace("\"E\"", "123"),
                command.replace("\"priority\":1", "\"priority\":1.5"),
                command.replace("\"早餐\"", "123"))) {
            assertThatThrownBy(() -> json.readValue(bad, MenuInternalApi.CreateCommand.class))
                    .isInstanceOf(RuntimeException.class);
        }
        for (String bad : List.of("1.5", "\"1\"", "9223372036854775808", "-1")) {
            assertThatThrownBy(() -> json.readValue("{\"total\":" + bad + ",\"page\":1,\"size\":20,\"records\":[]}",
                    MenuInternalApi.PageReceipt.class)).isInstanceOf(RuntimeException.class);
        }
        var api = mock(MenuInternalApi.class); var s = new RemoteMenuService(api);
        String incomplete = json.writeValueAsString(view("A", "早餐", 0)).replace("\"configVersion\":0", "\"other\":0");
        assertThatThrownBy(() -> json.readValue(incomplete, MenuVO.class)).isInstanceOf(RuntimeException.class);
    }
}

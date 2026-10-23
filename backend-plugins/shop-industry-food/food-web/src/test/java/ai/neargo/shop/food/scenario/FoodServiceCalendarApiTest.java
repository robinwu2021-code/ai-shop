package ai.neargo.shop.food.scenario;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FoodServiceCalendarApiTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FoodWebAutoConfiguration.class))
            .withUserConfiguration(FoodPolicyApiTest.PolicyHost.class, FoodPolicyApiTest.PolicySecurity.class)
            .withPropertyValues("shop.food.enabled=true", "spring.profiles.active=api");
    @Test void savesCalendarWithOriginalIdentityAndResponseEnvelope() {
        request("staff", null, "2026-10-20", "calendar-original",
                "{\"mode\":\"OPEN\",\"intervals\":[\"22:00-02:00\"],\"version\":0}",0);
    }
    @Test void deniesForeignStoreHeaderAndInsufficientPermission() {
        request("staff","OTHER","2026-10-20","calendar-original",
                "{\"mode\":\"CLOSED\",\"intervals\":[],\"version\":0}",10403);
        request("cashier",null,"2026-10-20","calendar-original",
                "{\"mode\":\"CLOSED\",\"intervals\":[],\"version\":0}",70006);
    }
    @Test void rejectsBadDateMissingKeyAndUnknownIdentity() {
        String body="{\"mode\":\"CLOSED\",\"intervals\":[],\"version\":0}";
        request("staff",null,"bad-date","calendar-original",body,10400);
        request("staff",null,"2026-10-20",null,body,10400);
        request("staff",null,"2026-10-20","calendar-original",body.replace("{","{\"actorNo\":\"forged\","),10400);
    }
    private void request(String actor,String header,String date,String key,String body,int code) {
        runner.run(context -> {
            String token=context.getBean(MemoryTokenStore.class)
                    .issue(TokenStore.SessionData.of(LoginUser.merchant(actor,actor)));
            var request=put("/biz/x/food/stores/A/calendar/"+date).header("Authorization","Bearer "+token)
                    .contentType(MediaType.APPLICATION_JSON).content(body);
            if (header!=null) request.header(BizContextFilter.STORE_HEADER,header);
            if (key!=null) request.header("Idempotency-Key",key);
            MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build()
                    .perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(code));
        });
    }
}

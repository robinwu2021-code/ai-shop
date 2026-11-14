package ai.neargo.shop.scenario;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.support.ShippingMerchantFixture;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** The actual merchant lookup used by App scanning; no inventory or merchant SKU lookup is substituted. */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetailStandardBarcodeFlowTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired OtpStore otp;
    MockMvc mvc;
    ShippingMerchantFixture.Scope scope;
    final String code = "99" + String.format("%011d", Math.floorMod(System.nanoTime(), 100_000_000_000L));
    final String archivedCode = Long.toString(Long.parseLong(code) - 1);
    final String marker = java.util.UUID.randomUUID().toString();
    final String title = "R03-standard-" + marker;

    @BeforeAll
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        scope = ShippingMerchantFixture.open(mvc, json, otp, "13400855001");
        seed("R03-S-" + code, code, "ACTIVE");
        seed("R03-A-" + code, archivedCode, "ARCHIVED");
    }

    @Test
    void exactBarcodeFindsActiveStandardWithoutInventingPriceOrStock() throws Exception {
        mvc.perform(get("/biz/spu-std").header("Authorization", "Bearer " + scope.token())
                        .header("X-Store-No", scope.store()).param("keyword", code).param("categoryNo", "CAT210"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].barcode").value(code))
                .andExpect(jsonPath("$.data[0].title").value(title))
                .andExpect(jsonPath("$.data[0].price").doesNotExist())
                .andExpect(jsonPath("$.data[0].stock").doesNotExist());
    }

    @Test
    void archivedPartialAndForeignCategoryRemainEmptyWhileAliasStillWorks() throws Exception {
        for (String keyword : new String[]{archivedCode, code.substring(0, 10)}) {
            mvc.perform(get("/biz/spu-std").header("Authorization", "Bearer " + scope.token())
                            .header("X-Store-No", scope.store()).param("keyword", keyword))
                    .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.length()").value(0));
        }
        mvc.perform(get("/biz/spu-std").header("Authorization", "Bearer " + scope.token())
                        .header("X-Store-No", scope.store()).param("keyword", code).param("categoryNo", "CAT220"))
                .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/biz/spu-std").header("Authorization", "Bearer " + scope.token())
                        .header("X-Store-No", scope.store()).param("keyword", "alias-" + marker))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    private void seed(String no, String barcode, String status) {
        jdbc.update("INSERT INTO prd_spu_std(std_no,category_no,title,keywords,barcode,status,spec_groups,"
                + "created_at,updated_at) VALUES (?,'CAT210',?,?,?,?,'[]',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                no, title, "alias-" + marker, barcode, status);
    }
}

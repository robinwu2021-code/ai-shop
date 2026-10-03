package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 证照识别（上传即读字段）。
 *
 * <p><b>这里不验「模型认得准不准」</b> —— 那要真图与真模型，测试环境两样都没有，
 * 而拿一个假网关去断言「识别正确」只会测到假网关自己。
 * 准确性是 2026-08-25 用真执照与真身份证对着模型验的（逐字段对过原图，全中），
 * 记在 {@code CertVisionGateway} 的类注释里。
 *
 * <p>这个类验的是**模型之外的那一圈**，而那一圈才是会悄悄出错的地方：
 * <ul>
 *   <li>没配模型时**如实说没认**，而不是返回一个空结果让端上以为认过了</li>
 *   <li>识别**不落任何东西** —— 不写资质、不建媒体记录</li>
 *   <li>不是图的字节要拒，而不是当成「没认出来」</li>
 *   <li>要权限：能传证的人才能让系统替他读证</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class CertRecognizeFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.spi.product.CertVisionPort certVision;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★ 没配模型时如实说「没认」—— 不能返回空结果让端上以为认过了")
    void tellsTheTruthWhenModelIsOff() throws Exception {
        // 测试环境本来就没配 shop.ai.vision.*，这一条正好覆盖生产上「模型挂了」的那一支
        assertThat(certVision.isEnabled()).as("测试环境不该连着真模型").isFalse();

        String token = merchant("12600190001", "识别·没配模型");
        String body = mvc().perform(multipart("/biz/qualifications/recognize")
                        .file(new MockMultipartFile("file", "lic.jpg", "image/jpeg", jpeg()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();

        var data = json.readTree(body).get("data");
        /*
         * ★ `recognized=false` 是端上决定「让他手填还是预填」的唯一依据。
         * 少了这一位、只回一堆 null 的话，端上分不清「模型说这张证上没有」
         * 与「压根没认」—— 前者该留空，后者该提示他自己填。
         */
        assertThat(data.get("recognized").asBoolean()).isFalse();
        assertThat(data.get("code").isNull()).isTrue();
    }

    @Test
    @DisplayName("★★★ 识别不落任何东西 —— 不写资质、不建媒体记录")
    void recognizePersistsNothing() throws Exception {
        String token = merchant("12600190002", "识别·不落库");

        long qualsBefore = qualCount(token);
        mvc().perform(multipart("/biz/qualifications/recognize")
                        .file(new MockMultipartFile("file", "id.jpg", "image/jpeg", jpeg()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0));

        /*
         * 这一条是整个设计的核心：身份证只在这一次请求里存在。
         * 哪天有人「顺手把识别过的图也存下来免得重传」，这条就会红 ——
         * 而那正是把身份证放进公开桶的第一步。
         */
        assertThat(qualCount(token)).as("识别不该产生任何资质记录").isEqualTo(qualsBefore);
    }

    @Test
    @DisplayName("★ 不是图的字节要拒 —— 后缀是客户端说了算的")
    void rejectsNonImageBytes() throws Exception {
        String token = merchant("12600190003", "识别·假图");
        mvc().perform(multipart("/biz/qualifications/recognize")
                        .file(new MockMultipartFile("file", "fake.jpg", "image/jpeg",
                                "这不是图片，只是一段文本".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
    }

    @Test
    @DisplayName("★ 没登录的人认不了证")
    void requiresLogin() throws Exception {
        mvc().perform(multipart("/biz/qualifications/recognize")
                        .file(new MockMultipartFile("file", "lic.jpg", "image/jpeg", jpeg())))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
    }

    // ------------------------------------------------------------ 脚手架

    /** 一张真的能被 ImageIO 解开的 jpeg —— 内容无所谓，这里测的不是识别本身 */
    private static byte[] jpeg() throws Exception {
        var img = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 120, 80);
        g.dispose();
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpeg", out);
        return out.toByteArray();
    }

    private long qualCount(String token) throws Exception {
        String body = mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/biz/qualifications").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        var items = json.readTree(body).get("data").get("items");
        return items == null ? 0 : items.size();
    }

    private String merchant(String phone, String name) throws Exception {
        String user = login(phone);
        String applyNo = json.readTree(mvc().perform(post("/mp/merchant/apply")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\","
                                + "\"communityNos\":[\"CM001\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("applyNo").asString();
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + opsLogin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        // A7：/biz/** 只认 btk_，这里必须换 B 端令牌
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }

    private String opsLogin() throws Exception {
        return json.readTree(mvc().perform(post("/ops/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bd\",\"password\":\"bd123\"}"))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("token").asString();
    }
}

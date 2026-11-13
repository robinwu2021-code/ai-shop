package ai.neargo.shop.media;

import ai.neargo.shop.channel.media.LocalDiskMediaStore;
import ai.neargo.shop.channel.media.CosMediaStore;
import ai.neargo.shop.channel.media.api.MediaReadController;
import ai.neargo.shop.config.UploadResourceConfig;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LocalMediaHttpReadTest {
    @TempDir Path root;
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({LocalDiskMediaStore.class, CosMediaStore.class, MediaReadController.class, UploadResourceConfig.class})
    static class WebConfig { }

    private AnnotationConfigWebApplicationContext context(String... properties) {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().setActiveProfiles("api");
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "shop.storage.root=" + root, "shop.media.sign-secret=test-signing-key");
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, properties);
        context.register(WebConfig.class);
        context.refresh();
        return context;
    }

    @Test void sharedRootServesGoodsButNeverExposesPrivateFilesAsPublic() throws Exception {
        try (var context = context()) {
            var store = context.getBean(MediaStore.class);
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            String goods = "E1/S1/goods/202611/image.png";
            byte[] image = {1, 2, 3};
            store.put(goods, new ByteArrayInputStream(image), image.length, "image/png");
            mvc.perform(get(store.publicUrl(goods))).andExpect(status().isOk()).andExpect(content().bytes(image));
            String proof = "E1/_ENTITY/qual/202611/proof.pdf";
            byte[] pdf = "%PDF-1.4\nfixture\n%%EOF".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            store.put(proof, new ByteArrayInputStream(pdf), pdf.length, "application/pdf");
            assertThat(root.resolve("uploads").resolve(proof)).hasBinaryContent(pdf);
            mvc.perform(get(store.publicUrl(proof))).andExpect(status().isNotFound());
            mvc.perform(get(store.privatePath(proof))).andExpect(status().isNotFound());
            mvc.perform(get(store.signedUrl(proof, Duration.ofMinutes(1))))
                    .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/pdf"))
                    .andExpect(content().bytes(pdf));
            mvc.perform(get(store.privatePath(proof)).param("exp", "1").param("sig", "bad"))
                    .andExpect(status().isNotFound());
            mvc.perform(get(store.privatePath(proof)).param("exp", Long.toString(System.currentTimeMillis() / 1000 + 60)).param("sig", "bad"))
                    .andExpect(status().isNotFound());
        }
    }

    @Test void avatarsEvidenceAndLegacyGoodsArePublicAndNotPrivateStreams() throws Exception {
        try (var context = context()) {
            var store = context.getBean(MediaStore.class);
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            for (String key : java.util.List.of("_USER/U1/avatar/202611/avatar.png",
                    "_USER/U1/evidence/review/202611/review.png", "M1/legacy.png")) {
                byte[] image = {1, 2, 3};
                store.put(key, new ByteArrayInputStream(image), image.length, "image/png");
                mvc.perform(get(store.publicUrl(key))).andExpect(status().isOk()).andExpect(content().bytes(image));
                mvc.perform(get(store.signedUrl(key, Duration.ofMinutes(1)))).andExpect(status().isNotFound());
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.openPrivate(key))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Test void businessProofsRemainSignedOnly() throws Exception {
        try (var context = context()) {
            var store = context.getBean(MediaStore.class);
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            for (String purpose : java.util.List.of(SysMediaAsset.QUAL.toLowerCase(java.util.Locale.ROOT), SysMediaAsset.AFTERSALE.toLowerCase(java.util.Locale.ROOT), SysMediaAsset.OFFLINE_RECEIPT.toLowerCase(java.util.Locale.ROOT), "reports")) {
                String key = "E1/S1/" + purpose + "/202611/proof.png";
                byte[] image = {4, 5, 6};
                store.put(key, new ByteArrayInputStream(image), image.length, "image/png");
                mvc.perform(get(store.publicUrl(key))).andExpect(status().isNotFound());
                mvc.perform(get(store.privatePath(key))).andExpect(status().isNotFound());
                mvc.perform(get(store.signedUrl(key, Duration.ofMinutes(1))))
                        .andExpect(status().isOk()).andExpect(content().bytes(image));
            }
        }
    }

    @Test void csvAndUnknownFilesAreNotReportedAsJpeg() throws Exception {
        try (var context = context()) {
            var store = context.getBean(MediaStore.class);
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            for (String extension : java.util.List.of("csv", "unknown")) {
                String key = "E1/_REPORT/reports/RE1/result." + extension;
                byte[] bytes = "id,total\n1,20".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                store.put(key, new ByteArrayInputStream(bytes), bytes.length, "application/octet-stream");
                mvc.perform(get(store.signedUrl(key, Duration.ofMinutes(1))))
                        .andExpect(status().isOk()).andExpect(content().bytes(bytes))
                        .andExpect(content().contentTypeCompatibleWith(extension.equals("csv") ? "text/csv" : "application/octet-stream"));
            }
        }
    }

    @Test void cosSwitchRemovesLocalPublicAndPrivateHandlers() throws Exception {
        try (var context = context("shop.media.provider=cos", "shop.cos.secret-id=test-id",
                "shop.cos.secret-key=test-key", "shop.cos.region=ap-guangzhou", "shop.cos.bucket=test-1300000000")) {
            assertThat(context.getBeansOfType(LocalDiskMediaStore.class)).isEmpty();
            assertThat(context.getBeansOfType(MediaReadController.class)).isEmpty();
            assertThat(context.getBeansOfType(UploadResourceConfig.class)).isEmpty();
            MockMvcBuilders.webAppContextSetup(context).build()
                    .perform(get("/uploads/E1/S1/goods/202611/image.png")).andExpect(status().isNotFound());
        }
    }
}

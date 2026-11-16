package ai.neargo.shop.portal.ops;

import ai.neargo.shop.auth.PermChecker;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.platform.MarketPhonePolicyService;
import ai.neargo.shop.portal.biz.BizCertController;
import ai.neargo.shop.portal.common.CertDocumentReader;
import ai.neargo.shop.spi.product.CertVisionPort;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpsOnboardingDocumentControllerTest {
    @Test void permissionsAndTaiwanOnly() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(Security.class)) {
            var vision = context.getBean(CertVisionPort.class);
            var market = context.getBean(MarketPhonePolicyService.class);
            var perm = context.getBean(PermChecker.class);
            var controller = context.getBean(OpsOnboardingDocumentController.class);
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("operator", "unused", "ROLE_OPS"));
            assertThatThrownBy(() -> controller.recognize(image(), CertVisionPort.DocumentType.REGISTRATION, new MockHttpServletResponse())).isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(vision, market);
            when(perm.can(Perms.MERCHANT_ADMISSION_UPDATE)).thenReturn(true);
            when(market.instancePolicy()).thenReturn(policy("CN"));
            assertThatThrownBy(() -> controller.recognize(image(), CertVisionPort.DocumentType.REGISTRATION, new MockHttpServletResponse())).isInstanceOf(BizException.class);
            verifyNoInteractions(vision);
            when(market.instancePolicy()).thenReturn(policy("TW"));
            when(vision.isEnabled()).thenReturn(true);when(vision.supportsDocuments()).thenReturn(true);
            when(vision.recognizeDocument(any(),eq("image/jpeg"),eq(CertVisionPort.DocumentType.REGISTRATION)))
                    .thenReturn(new CertVisionPort.Document(CertVisionPort.DocumentType.REGISTRATION, Map.of("taxNo", "12345678"), 0.8));
            var response = new MockHttpServletResponse();
            assertThat(controller.recognize(image(), CertVisionPort.DocumentType.REGISTRATION, response).fields()).containsEntry("taxNo", "12345678");
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Test void invalidDocumentsNeverReachProvider() throws Exception {
        var vision = mock(CertVisionPort.class);var market = mock(MarketPhonePolicyService.class);
        when(market.instancePolicy()).thenReturn(policy("TW"));
        var controller = new OpsOnboardingDocumentController(vision, market);
        for (String mime : new String[]{"image/jpeg", "application/pdf"}) {
            var bad = new MockMultipartFile("file", "invalid", mime, "not a document".getBytes());
            assertThatThrownBy(() -> controller.recognize(bad, CertVisionPort.DocumentType.BANK_COVER, new MockHttpServletResponse())).isInstanceOf(BizException.class);
        }
        assertThatThrownBy(() -> CertDocumentReader.read(new MockMultipartFile("file", "bad.pdf", "application/pdf", "%PDF-1.4 broken".getBytes()))).isInstanceOf(BizException.class);
        verifyNoInteractions(vision);
    }

    @Test void validPdfIsInlineAndDisabledProviderDoesNotRecognize() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var pdf = new PDDocument()) { pdf.addPage(new PDPage());pdf.save(out); }
        var input = new MockMultipartFile("file", "bank.pdf", "application/pdf", out.toByteArray());
        var prepared = CertDocumentReader.read(input);
        assertThat(prepared.bytes()).isEqualTo(out.toByteArray());assertThat(prepared.contentType()).isEqualTo("application/pdf");
        var vision = mock(CertVisionPort.class);var market = mock(MarketPhonePolicyService.class);when(market.instancePolicy()).thenReturn(policy("TW"));
        assertThat(new OpsOnboardingDocumentController(vision, market).recognize(input, CertVisionPort.DocumentType.BANK_COVER, new MockHttpServletResponse()).recognized()).isFalse();
        verify(vision,never()).recognizeDocument(any(),any(),any());
        assertThat(new BizCertController(vision).recognize(image()).recognized()).isFalse();
    }

    private static MarketPhonePolicyService.Policy policy(String market) { return new MarketPhonePolicyService.Policy(market,"",true,false,false,true); }
    private static MockMultipartFile image() throws Exception {
        var out = new ByteArrayOutputStream();ImageIO.write(new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB), "png", out);
        return new MockMultipartFile("file", "input.png", "image/png", out.toByteArray());
    }
    @Configuration(proxyBeanMethods=false) @EnableMethodSecurity
    static class Security {
        @Bean CertVisionPort vision() { return mock(CertVisionPort.class); }
        @Bean MarketPhonePolicyService markets() { return mock(MarketPhonePolicyService.class); }
        @Bean(name="perm") PermChecker permissions() { return mock(PermChecker.class); }
        @Bean OpsOnboardingDocumentController controller(CertVisionPort v, MarketPhonePolicyService m) { return new OpsOnboardingDocumentController(v,m); }
    }
}

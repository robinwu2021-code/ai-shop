package ai.neargo.shop.portal.biz;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.neargo.auth.store.SubjectKind;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.merchant.paysync.StoreSyncProperties;
import ai.neargo.shop.merchant.service.MerchantEntityService;
import ai.neargo.shop.payclient.PayEntityProfileApi;
import ai.neargo.shop.platform.MarketPhonePolicyService;
import ai.neargo.shop.portal.biz.BizEntityRecognitionController.Command;
import ai.neargo.shop.portal.biz.BizEntityRecognitionController.EntityRecognitionType;
import ai.neargo.shop.spi.product.CertVisionPort;
import ai.neargo.shop.spi.product.CertVisionPort.DocumentType;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class BizEntityRecognitionControllerTest {
  private static final String REF = "file:entity-profile/0123456789abcdef0123456789abcdef";
  private final CertVisionPort vision = mock(CertVisionPort.class);
  private final MarketPhonePolicyService markets = mock(MarketPhonePolicyService.class);
  private final MerchantEntityService entities = mock(MerchantEntityService.class);
  private final PayEntityProfileApi pay = mock(PayEntityProfileApi.class);
  private final StoreSyncProperties properties = mock(StoreSyncProperties.class);
  private final BizEntityRecognitionController controller =
      new BizEntityRecognitionController(vision, markets, entities, pay, properties);

  @BeforeEach
  void owner() {
    var user = new LoginUser(Realm.MERCHANT, SubjectKind.USR, "U1", "o", List.of(), List.of(), null, null);
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    when(markets.instancePolicy()).thenReturn(new MarketPhonePolicyService.Policy("TW", "", true, false, false, true));
    when(properties.sourceScope()).thenReturn("SCOPE");
    when(vision.isEnabled()).thenReturn(true);
    when(vision.supportsDocuments()).thenReturn(true);
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void beforeCreateReadsOnlyRegistration() throws Exception {
    when(vision.recognizeDocument(any(), eq("image/jpeg"), eq(DocumentType.ENTITY_REGISTRATION)))
        .thenReturn(new CertVisionPort.Document(DocumentType.ENTITY_REGISTRATION,
            Map.of("taxNo", "01234567", "registeredCity", "臺北市"), 0.95));
    var response = new MockHttpServletResponse();
    var result = controller.beforeCreate(image(), EntityRecognitionType.REGISTRATION, "R1", response);
    assertThat(result.recognized()).isTrue();
    assertThat(result.docType()).isEqualTo(EntityRecognitionType.REGISTRATION);
    assertThat(result.fields()).containsEntry("taxNo", "01234567").containsEntry("registeredCity", "臺北市");
    assertThat(result.lowConfidence()).isEmpty();
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThatThrownBy(() -> controller.beforeCreate(image(), EntityRecognitionType.BANK_COVER, "R2", new MockHttpServletResponse()))
        .isInstanceOf(BizException.class);
    verifyNoInteractions(pay, entities);
  }

  @Test
  void uploadedFileIsReadThroughPayForOwner() throws Exception {
    when(pay.content("E1", "0123456789abcdef0123456789abcdef", "SCOPE", "U1"))
        .thenReturn(ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(image().getBytes()));
    when(vision.recognizeDocument(any(), eq("image/jpeg"), eq(DocumentType.BANK_COVER)))
        .thenReturn(new CertVisionPort.Document(DocumentType.BANK_COVER, Map.of("bankAccount", "000123"), 0.6));
    var result = controller.uploaded("E1", new Command("R1", REF, EntityRecognitionType.BANK_COVER), new MockHttpServletResponse());
    verify(entities).detail("U1", "E1");
    assertThat(result.fields()).containsExactly(Map.entry("bankAccount", "000123"));
    assertThat(result.lowConfidence()).containsExactly("bankAccount");
  }

  @Test
  void badReferenceIsBadRequest() {
    assertThatThrownBy(() -> controller.uploaded("E1", new Command("R1", "file:other/abc", EntityRecognitionType.BANK_COVER), new MockHttpServletResponse()))
        .isInstanceOf(BizException.class);
    verifyNoInteractions(pay, vision);
  }

  @Test
  void unrecognizedIsEmptyNotError() throws Exception {
    when(vision.recognizeDocument(any(), any(), any())).thenReturn(null);
    var none = controller.beforeCreate(image(), EntityRecognitionType.REGISTRATION, "R1", new MockHttpServletResponse());
    assertThat(none.recognized()).isFalse();
    assertThat(none.fields()).isEmpty();
    when(vision.isEnabled()).thenReturn(false);
    assertThat(controller.beforeCreate(image(), EntityRecognitionType.REGISTRATION, "R2", new MockHttpServletResponse()).recognized()).isFalse();
  }

  @Test
  void valuesFollowAppContract() {
    assertThat(BizEntityRecognitionController.normalize(Map.of(
            "idIssueType", "換發", "issuePlace", "(北市)", "birthDate", "1985-06-01", "issueDate", "74年6月1日",
            "capitalAmount", "1,000,000", "legalForm", "ENTERPRISE", "identityNo", "A123456789")))
        .containsOnly(
            Map.entry("idIssueType", "RENEWAL"), Map.entry("issuePlace", "北市"), Map.entry("birthDate", "1985-06-01"),
            Map.entry("legalForm", "ENTERPRISE"), Map.entry("identityNo", "A123456789"));
    assertThat(BizEntityRecognitionController.normalize(Map.of("legalForm", "公司", "idIssueType", "其他"))).isEmpty();
  }

  private static MockMultipartFile image() throws Exception {
    var out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB), "png", out);
    return new MockMultipartFile("file", "input.png", "image/png", out.toByteArray());
  }
}

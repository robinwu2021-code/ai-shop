package ai.neargo.shop.portal.biz;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.paysync.StoreSyncProperties;
import ai.neargo.shop.merchant.service.EntityRegistrationProjection;
import ai.neargo.shop.merchant.service.MerchantEntityService;
import ai.neargo.shop.payclient.EntityProfileRemoteCalls;
import ai.neargo.shop.payclient.PayEntityProfileApi;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Account-owned entity facade. Store selection cannot redirect a material write. */
@Profile("api")
@RestController
@Validated
@ConditionalOnProperty(name = "shop.pay.entity-profiles.enabled", havingValue = "true")
@RequestMapping("/biz/entities/{entity}")
@PreAuthorize("@perm.merchantAccount()")
public class BizEntityProfileController {
  private final MerchantEntityService entities;
  private final PayEntityProfileApi pay;
  private final StoreSyncProperties properties;
  private final EntityRegistrationProjection registration;

  public BizEntityProfileController(
      MerchantEntityService entities,
      PayEntityProfileApi pay,
      StoreSyncProperties properties,
      EntityRegistrationProjection registration) {
    this.entities = entities;
    this.pay = pay;
    this.properties = properties;
    this.registration = registration;
  }

  public record Command(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String requestNo,
      @Min(0) long expectedVersion,
      @NotBlank @Size(max = 32) String schemaVersion,
      @Size(max = 120)
          Map<@NotBlank @Size(max = 64) String, @NotNull @Size(max = 512) String> fields,
      @Size(max = 30)
          Map<@NotBlank @Size(max = 64) String, @NotBlank @Size(max = 128) String> documents,
      @Size(max = 3) List<@NotBlank String> channels,
      @Size(max = 64) String consentVersion,
      Boolean confirmed) {
    @Override
    public String toString() {
      return "EntityProfileCommand[REDACTED]";
    }
  }

  private String owner(String entity) {
    var user = BizAuthController.personalUser();
    entities.detail(user.userNo(), entity);
    return user.userNo();
  }

  private Map<String, Object> command(String entity, Command c) {
    String actor = owner(entity);
    var body = new LinkedHashMap<String, Object>();
    body.put("sourceScope", properties.sourceScope());
    body.put("actorRef", actor);
    body.put("requestNo", c.requestNo);
    body.put("expectedVersion", c.expectedVersion);
    body.put("schemaVersion", c.schemaVersion);
    if (c.fields != null) body.put("fields", c.fields);
    if (c.documents != null) body.put("documents", c.documents);
    if (c.channels != null) body.put("channels", c.channels);
    if (c.consentVersion != null) body.put("consentVersion", c.consentVersion);
    body.put("confirmed", Boolean.TRUE.equals(c.confirmed));
    return body;
  }

  @GetMapping("/profile-template")
  public Map<String, Object> template(
      @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String entity) {
    owner(entity);
    return EntityProfileRemoteCalls.call(() -> pay.template(entity, properties.sourceScope()));
  }

  @GetMapping("/profile-draft")
  public Map<String, Object> read(
      @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String entity) {
    var user = BizAuthController.personalUser();
    var identity = entities.detail(user.userNo(), entity).entity();
    var result =
        new LinkedHashMap<String, Object>(
            EntityProfileRemoteCalls.call(() -> pay.read(entity, properties.sourceScope())));
    var fields = new LinkedHashMap<String, Object>();
    if (result.get("fields") instanceof Map<?, ?> saved)
      saved.forEach((k, v) -> fields.put((String) k, v));
    fields.putIfAbsent("name", identity.name());
    if (identity.legalForm() != null) fields.putIfAbsent("legalForm", identity.legalForm());
    result.put("fields", fields);
    return result;
  }

  @PostMapping("/profile-draft")
  public Map<String, Object> save(
      @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String entity,
      @RequestBody @Valid Command c) {
    return EntityProfileRemoteCalls.call(() -> pay.save(entity, command(entity, c)));
  }

  @PostMapping("/review-submissions")
  public Map<String, Object> submit(
      @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String entity,
      @RequestBody @Valid Command c) {
    var body = command(entity, c);
    registration.observe(
        BizAuthController.personalUser().tenantNo(),
        entity,
        (String) body.get("actorRef"),
        c.requestNo());
    return EntityProfileRemoteCalls.call(() -> pay.submit(entity, body));
  }

  @GetMapping("/commands/{request}")
  public Map<String, Object> result(
      @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String entity,
      @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String request) {
    return EntityProfileRemoteCalls.call(
        () -> pay.result(entity, request, properties.sourceScope(), owner(entity)));
  }

  @PostMapping(value = "/files", consumes = "multipart/form-data")
  public Map<String, Object> upload(
      @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String entity,
      @RequestParam @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String requestNo,
      @RequestParam @NotBlank @Size(max = 64) String purpose,
      @RequestPart("file") MultipartFile file)
      throws IOException {
    String actor = owner(entity);
    if (file.getSize() > 5242880) throw BizException.of(ErrorCode.BAD_REQUEST);
    byte[] bytes = file.getBytes();
    var resource =
        new ByteArrayResource(bytes) {
          @Override
          public String getFilename() {
            return file.getOriginalFilename();
          }
        };
    try {
      return EntityProfileRemoteCalls.call(
          () -> pay.upload(entity, properties.sourceScope(), actor, requestNo, purpose, resource));
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  @PostMapping("/revisions")
  public Map<String, Object> revise(
      @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String entity,
      @RequestBody @Valid Command c) {
    return EntityProfileRemoteCalls.call(() -> pay.revise(entity, command(entity, c)));
  }

  @PostMapping("/channel-requests")
  public Map<String, Object> addChannels(
      @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String entity,
      @RequestBody @Valid Command c) {
    return EntityProfileRemoteCalls.call(() -> pay.addChannels(entity, command(entity, c)));
  }

  @ModelAttribute
  public void privateResponse(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
  }
}

package ai.neargo.shop.payclient;

import java.util.List;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.service.annotation.*;

/** Trusted PAY entity-profile contract. No public caller can select source scope or actor. */
public interface PayEntityProfileApi {
  String ROOT = "/internal/pay/v2/entity-profiles/{entity}";

  @GetExchange(ROOT + "/profile-template")
  Map<String, Object> template(
      @PathVariable("entity") String entity, @RequestParam("sourceScope") String scope);

  @GetExchange(ROOT + "/profile-draft")
  Map<String, Object> read(
      @PathVariable("entity") String entity, @RequestParam("sourceScope") String scope);

  @PostExchange(ROOT + "/profile-draft")
  Map<String, Object> save(
      @PathVariable("entity") String entity, @RequestBody Map<String, Object> body);

  @PostExchange(ROOT + "/review-submissions")
  Map<String, Object> submit(
      @PathVariable("entity") String entity, @RequestBody Map<String, Object> body);

  @GetExchange(ROOT + "/commands/{request}")
  Map<String, Object> result(
      @PathVariable("entity") String entity,
      @PathVariable("request") String request,
      @RequestParam("sourceScope") String scope,
      @RequestParam("actorRef") String actor);

  @PostExchange(value = ROOT + "/files", contentType = "multipart/form-data")
  Map<String, Object> upload(
      @PathVariable("entity") String entity,
      @RequestParam("sourceScope") String scope,
      @RequestParam("actorRef") String actor,
      @RequestParam("requestNo") String request,
      @RequestParam("purpose") String purpose,
      @RequestPart("file") Resource file);

  @PostExchange(ROOT + "/revisions")
  Map<String, Object> revise(
      @PathVariable("entity") String entity, @RequestBody Map<String, Object> body);

  @PostExchange(ROOT + "/channel-requests")
  Map<String, Object> addChannels(
      @PathVariable("entity") String entity, @RequestBody Map<String, Object> body);

  /** The entity contact, outside the immutable material revisions (V46). */
  @PostExchange(ROOT + "/contact")
  Map<String, Object> contact(
      @PathVariable("entity") String entity, @RequestBody Map<String, Object> body);

  /** Discards a revision draft; PAY refuses one under review or with an unknown result. */
  @PostExchange(ROOT + "/revision-discards")
  Map<String, Object> discardRevision(
      @PathVariable("entity") String entity, @RequestBody Map<String, Object> body);

  /** Owner-only thumbnail of an uploaded evidence file; 204 when there is none (PDF). */
  @GetExchange(ROOT + "/files/{file}/preview")
  ResponseEntity<byte[]> preview(
      @PathVariable("entity") String entity,
      @PathVariable("file") String file,
      @RequestParam("sourceScope") String scope,
      @RequestParam("actorRef") String actor);

  /** Owner-only original bytes of an uploaded evidence file, for server-side recognition only. */
  @GetExchange(ROOT + "/files/{file}/content")
  ResponseEntity<byte[]> content(
      @PathVariable("entity") String entity,
      @PathVariable("file") String file,
      @RequestParam("sourceScope") String scope,
      @RequestParam("actorRef") String actor);

  record SummaryQuery(String sourceScope, List<String> entities) {}

  @PostExchange("/internal/pay/v2/entity-profiles/summaries")
  Map<String, Map<String, Object>> summaries(@RequestBody SummaryQuery body);

  @GetExchange(ROOT + "/approved-registration")
  Map<String, Object> approvedRegistration(
      @PathVariable("entity") String entity, @RequestParam("sourceScope") String scope);
}

package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.AuthorizeCartSessionReq;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.CartResultVO;
import ai.neargo.shop.food.dto.CartSessionGrantVO;
import ai.neargo.shop.food.dto.CartVersionReq;
import ai.neargo.shop.food.dto.CartVersionVO;
import ai.neargo.shop.food.dto.CreateCartReq;
import ai.neargo.shop.food.dto.CreateCustomerCartReq;
import ai.neargo.shop.food.dto.OperationResultVO;
import ai.neargo.shop.food.dto.ReplaceCartLineReq;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Typed commands preserve authenticated ownership without exposing persistence entities to remote callers. */
public interface CartInternalApi {
    String CREATE_PATH = "/internal/food/carts/create";
    @PostExchange(CREATE_PATH)
    CartResultVO create(@RequestBody CreateCommand command);
    String GET_PATH = "/internal/food/carts/get";
    @PostExchange(GET_PATH)
    CartResultVO get(@RequestBody Identity command);
    String REPLACE_PATH = "/internal/food/carts/replace-line";
    @PostExchange(REPLACE_PATH)
    CartVersionVO replaceLine(@RequestBody ReplaceLineCommand command);
    String ARCHIVE_LINE_PATH = "/internal/food/carts/archive-line";
    @PostExchange(ARCHIVE_LINE_PATH)
    CartVersionVO archiveLine(@RequestBody ArchiveLineCommand command);
    String ARCHIVE_PATH = "/internal/food/carts/archive";
    @PostExchange(ARCHIVE_PATH)
    OperationResultVO archive(@RequestBody ArchiveCommand command);
    String AUTHORIZE_PATH = "/internal/food/carts/authorize-session";
    @PostExchange(AUTHORIZE_PATH)
    CartSessionGrantVO authorize(@RequestBody AuthorizeCommand command);
    String CUSTOMER_CREATE_PATH = "/internal/food/carts/customer-create";
    @PostExchange(CUSTOMER_CREATE_PATH)
    CartResultVO customerCreate(@RequestBody CustomerCreateCommand command);
    String CUSTOMER_GET_PATH = "/internal/food/carts/customer-get";
    @PostExchange(CUSTOMER_GET_PATH)
    CartResultVO customerGet(@RequestBody CustomerIdentity command);
    String CUSTOMER_REPLACE_PATH = "/internal/food/carts/customer-replace-line";
    @PostExchange(CUSTOMER_REPLACE_PATH)
    CartVersionVO customerReplaceLine(@RequestBody CustomerReplaceLineCommand command);
    String CUSTOMER_ARCHIVE_LINE_PATH = "/internal/food/carts/customer-archive-line";
    @PostExchange(CUSTOMER_ARCHIVE_LINE_PATH)
    CartVersionVO customerArchiveLine(@RequestBody CustomerArchiveLineCommand command);

    record CreateCommand(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 32) String entityNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 32) String storeNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 32) String actorNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @NotNull CreateCartReq request) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown cart command field");
        }
    }

    record Identity(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 32) String entityNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 32) String storeNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 32) String cartNo) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown cart command field");
        }
    }

    record ReplaceLineCommand(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 32) String entityNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 32) String storeNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 32) String cartNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 64) String sourceLineRef,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 32) String actorNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @NotNull ReplaceCartLineReq request) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown cart command field");
        }
    }

    record ArchiveLineCommand(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 32) String entityNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 32) String storeNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 32) String cartNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 64) String sourceLineRef,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 32) String actorNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @NotNull CartVersionReq request) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown cart command field");
        }
    }

    record ArchiveCommand(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 32) String entityNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 32) String storeNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 32) String cartNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 32) String actorNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @NotNull CartVersionReq request) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown cart command field");
        }
    }

    record AuthorizeCommand(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 32) String entityNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 32) String storeNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 32) String checkNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 32) String actorNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @NotNull AuthorizeCartSessionReq request) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown cart command field");
        }
    }

    record CustomerCreateCommand(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 32) String storeNo,
            @Valid @NotNull CartAccess access,
            @Valid @NotNull CreateCustomerCartReq request,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 128) String idempotencyKey) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown cart command field");
        }
        @Override public String toString() { return "CustomerCreateCommand[REDACTED]"; }
    }

    record CustomerIdentity(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 32) String cartNo,
            @Valid @NotNull CartAccess access) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown cart command field");
        }
        @Override public String toString() { return "CustomerIdentity[REDACTED]"; }
    }

    record CustomerReplaceLineCommand(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 32) String cartNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 64) String sourceLineRef,
            @Valid @NotNull CartAccess access,
            @Valid @NotNull ReplaceCartLineReq request,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 128) String idempotencyKey) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown cart command field");
        }
        @Override public String toString() { return "CustomerReplaceLineCommand[REDACTED]"; }
    }

    record CustomerArchiveLineCommand(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 32) String cartNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 64) String sourceLineRef,
            @Valid @NotNull CartAccess access,
            @Valid @NotNull CartVersionReq request,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 128) String idempotencyKey) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown cart command field");
        }
        @Override public String toString() { return "CustomerArchiveLineCommand[REDACTED]"; }
    }
}

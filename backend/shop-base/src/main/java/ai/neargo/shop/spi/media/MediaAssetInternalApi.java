package ai.neargo.shop.spi.media;

import ai.neargo.shop.spi.media.MediaAssetQueryPort.AssetFact;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** The platform remains the media authority when restaurant configuration runs independently. */
public interface MediaAssetInternalApi {
    String BATCH_PATH = "/internal/media/assets/batch";

    @PostExchange(BATCH_PATH)
    List<AssetFact> lookupBatch(@RequestBody BatchLookup request);

    record BatchLookup(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @NotNull @Size(min=1, max=20) List<@NotBlank @Size(max=512) String> assetKeys) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown media lookup field");
        }
    }
}

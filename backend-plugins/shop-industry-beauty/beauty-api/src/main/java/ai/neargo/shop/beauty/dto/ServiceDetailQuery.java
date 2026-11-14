package ai.neargo.shop.beauty.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Public links require an explicit store; they never inherit a merchant's default store. */
public record ServiceDetailQuery(@NotBlank @Size(max = 32) String storeNo) { }

package ai.neargo.shop.merchant.service;

import ai.neargo.commerce.api.common.ActorContext;
import jakarta.validation.constraints.*;
import java.util.List;

/** 门店行业字典，与支付行业 sys_industry 分开维护。 */
public interface StoreIndustryAdminService {
    record View(String industryCode, String subIndustryCode, String businessTypeCode,
                String nameZh, String nameTw, String nameEn, int sort, boolean enabled, long version) {}

    record Save(
            @NotBlank @Pattern(regexp="[A-Za-z0-9_.:-]{1,32}") String industryCode,
            @Pattern(regexp="[A-Za-z0-9_.:-]{0,48}") String subIndustryCode,
            @NotBlank @Pattern(regexp="[A-Za-z0-9_.:-]{1,32}") String businessTypeCode,
            @NotBlank @Size(max=32) String nameZh,
            @NotBlank @Size(max=32) String nameTw,
            @Size(max=64) String nameEn,
            @NotNull @Min(0) Integer sort,
            @NotNull Boolean enabled,
            @PositiveOrZero Long expectedVersion,
            @NotBlank @Size(max=1024) String reason) {}

    List<View> list(ActorContext actor);
    View save(ActorContext actor, Save command);
}

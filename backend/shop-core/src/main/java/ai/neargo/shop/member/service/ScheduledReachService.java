package ai.neargo.shop.member.service;

import ai.neargo.shop.spi.member.MemberQueryPort.AudienceItem;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public interface ScheduledReachService {
    record Request(@NotEmpty List<@NotNull AudienceItem> audiences, @Size(max = 128) String audienceDesc,
                   @NotNull @Pattern(regexp = "NOTICE|WAKEUP|COUPON") String scene,
                   @NotBlank @Size(max = 64) String title, @Size(max = 255) String body,
                   @NotNull LocalDateTime localTime) {}

    String schedule(String entityNo, Request request, String operatorNo, String requestKey);
    void cancel(String entityNo, String taskNo);
    int dispatchDue(int maxRecipientsPerTask);
}

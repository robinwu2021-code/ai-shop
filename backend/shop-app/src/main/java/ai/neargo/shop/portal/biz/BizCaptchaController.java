package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.ClientMeta;
import ai.neargo.shop.auth.captcha.BizCaptchaService;
import cloud.tianai.captcha.validator.common.model.dto.ImageCaptchaTrack;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@Profile("api")
@RestController
public class BizCaptchaController {
    private final BizCaptchaService captcha;
    public BizCaptchaController(BizCaptchaService captcha) { this.captcha = captcha; }

    public record ChallengeReq(@NotNull BizCaptchaService.Action action, @NotBlank @Size(max=191) String principal) {}
    public record VerifyReq(@NotBlank @Size(max=64) String challengeId, @NotNull BizCaptchaService.Action action,
                            @NotBlank @Size(max=191) String principal, @NotNull @Valid Track track) {}
    public record Point(@NotNull @DecimalMin("-1024") @DecimalMax("1024") Float x,
                        @NotNull @DecimalMin("-1024") @DecimalMax("1024") Float y,
                        @NotNull @DecimalMin("0") @DecimalMax("120000") Float t,
                        @NotBlank @Pattern(regexp="DOWN|MOVE|UP") String type) {}
    public record Track(@NotNull @Min(1) @Max(1024) Integer bgImageWidth,
                        @NotNull @Min(1) @Max(1024) Integer bgImageHeight,
                        @NotNull @Min(1) @Max(1024) Integer templateImageWidth,
                        @NotNull @Min(1) @Max(1024) Integer templateImageHeight,
                        @NotNull @Positive Long startTime, @NotNull @Positive Long stopTime,
                        @NotNull @Min(0) @Max(1024) Integer left, @NotNull @Min(0) @Max(0) Integer top,
                        @NotNull @Size(min=2,max=1600) List<@NotNull @Valid Point> trackList) {
        ImageCaptchaTrack toEngine() {
            var track = new ImageCaptchaTrack();
            track.setBgImageWidth(bgImageWidth); track.setBgImageHeight(bgImageHeight);
            track.setTemplateImageWidth(templateImageWidth); track.setTemplateImageHeight(templateImageHeight);
            track.setStartTime(startTime); track.setStopTime(stopTime); track.setLeft(left); track.setTop(top);
            track.setTrackList(trackList.stream().map(p -> new ImageCaptchaTrack.Track(p.x(),p.y(),p.t(),p.type())).toList());
            return track;
        }
    }

    @PostMapping("/biz/auth/captcha/challenge")
    public BizCaptchaService.Challenge challenge(@RequestBody @Valid ChallengeReq request, HttpServletRequest http) {
        return captcha.issue(request.action(), request.principal(), ClientMeta.clientIp(http));
    }

    @PostMapping("/biz/auth/captcha/verify")
    public BizCaptchaService.Proof verify(@RequestBody @Valid VerifyReq request, HttpServletRequest http) {
        return captcha.verify(request.challengeId(), request.action(), request.principal(), request.track().toEngine(), ClientMeta.clientIp(http));
    }
}

package ai.neargo.shop.auth.captcha.impl;

import ai.neargo.shop.auth.captcha.BizCaptchaService;
import ai.neargo.shop.auth.captcha.CaptchaProofService;
import ai.neargo.shop.common.otp.OtpKeys;
import ai.neargo.shop.auth.captcha.JdbcCaptchaStore;
import ai.neargo.shop.auth.captcha.TianaiSliderEngine;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.captcha.CaptchaRequiredException;
import cloud.tianai.captcha.common.AnyMap;
import cloud.tianai.captcha.validator.common.model.dto.ImageCaptchaTrack;
import cloud.tianai.captcha.validator.impl.BasicCaptchaTrackValidator;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import tools.jackson.databind.ObjectMapper;

public class BizCaptchaServiceImpl implements BizCaptchaService {
    private static final int CHALLENGE_SECONDS = 120;
    private static final int PROOF_SECONDS = 60;
    private final JdbcCaptchaStore store;
    private final TianaiSliderEngine engine;
    private final ObjectMapper json;
    private final BasicCaptchaTrackValidator validator = new BasicCaptchaTrackValidator(0.015f);
    private final boolean enabled;
    private final UnaryOperator<String> normalizeEmail;

    public BizCaptchaServiceImpl(JdbcCaptchaStore store, TianaiSliderEngine engine, ObjectMapper json, boolean enabled, UnaryOperator<String> normalizeEmail) {
        this.store = store; this.engine = engine; this.json = json; this.enabled = enabled; this.normalizeEmail = normalizeEmail;
    }

    @Override public Challenge issue(Action action, String principal, String clientIp, String scene, String businessKey) {
        rate("issue", action, principal, clientIp, 10, 30);
        var image = engine.generate();
        String id = UUID.randomUUID().toString();
        var state = new ChallengeState(validator.generateImageCaptchaValidData(image),
                image.getBackgroundImageWidth(), image.getBackgroundImageHeight(), image.getTemplateImageWidth(), image.getTemplateImageHeight());
        store.save(id, scope("challenge", action, principal, scene, businessKey), json.writeValueAsString(state), CHALLENGE_SECONDS);
        return new Challenge(id, image.getBackgroundImage(), image.getTemplateImage(), state.width(), state.height(),
                state.templateWidth(), state.templateHeight(), CHALLENGE_SECONDS);
    }

    @Override public Proof verify(String id, Action action, String principal, ImageCaptchaTrack track, String clientIp, String scene, String businessKey) {
        rate("verify", action, principal, clientIp, 20, 60);
        String payload = store.consume(id, scope("challenge", action, principal, scene, businessKey));
        if (payload == null) throw BizException.of(ErrorCode.CAPTCHA_INVALID);
        var state = json.readValue(payload, ChallengeState.class);
        if (!dimensionsMatch(track, state) || !validator.valid(track, new AnyMap(state.validData())).isSuccess())
            throw BizException.of(ErrorCode.CAPTCHA_INVALID);
        String token = UUID.randomUUID() + "." + UUID.randomUUID();
        store.save(token, scope("proof", action, principal, scene, businessKey), "verified", PROOF_SECONDS);
        return new Proof(token, PROOF_SECONDS);
    }

    @Override public void require(Action action, String principal, String token) {
        if (enabled) new CaptchaProofService(store).require(action.name(), canonical(action, principal), null, null, token);
    }

    private void rate(String stage, Action action, String principal, String ip, int accountMax, int ipMax) {
        // Source-wide limit cannot be bypassed by cycling targets or actions.
        store.limit("captcha:" + stage + ":ip:" + ip, ipMax);
        store.limit("captcha:" + stage + ":principal:" + canonical(action, principal), accountMax);
    }

    private String scope(String kind, Action action, String principal, String scene, String businessKey) {
        return OtpKeys.captchaScope(kind, action.name(), canonical(action, principal), scene, businessKey);
    }

    private String canonical(Action action, String principal) {
        // Phone OTP uses the actual unmodified target; password credentials trim the principal.
        return action == Action.EMAIL_OTP_SEND || (action == Action.LOGIN && principal != null && principal.contains("@"))
                ? normalizeEmail.apply(principal) : action == Action.LOGIN ? principal.trim() : principal;
    }

    private static boolean dimensionsMatch(ImageCaptchaTrack track, ChallengeState state) {
        return track != null && Integer.valueOf(state.width()).equals(track.getBgImageWidth())
                && Integer.valueOf(state.height()).equals(track.getBgImageHeight())
                && Integer.valueOf(state.templateWidth()).equals(track.getTemplateImageWidth())
                && Integer.valueOf(state.templateHeight()).equals(track.getTemplateImageHeight());
    }

    public record ChallengeState(Map<String, Object> validData, int width, int height, int templateWidth, int templateHeight) {}
}

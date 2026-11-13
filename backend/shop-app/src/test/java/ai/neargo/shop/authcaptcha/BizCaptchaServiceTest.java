package ai.neargo.shop.authcaptcha;

import ai.neargo.shop.auth.captcha.*;
import ai.neargo.shop.user.service.EmailOtpService;
import ai.neargo.shop.auth.captcha.impl.BizCaptchaServiceImpl;

import static org.assertj.core.api.Assertions.*;

import ai.neargo.shop.common.BizException;
import cloud.tianai.captcha.validator.common.model.dto.ImageCaptchaTrack;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

class BizCaptchaServiceTest {
    JdbcTemplate jdbc;
    JdbcCaptchaStore store;
    BizCaptchaServiceImpl service;
    final MutableClock clock = new MutableClock();
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        String ddl = new ClassPathResource("db/migration/V663__biz_auth_captcha.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        for (String sql : ddl.split(";")) if (!sql.isBlank()) jdbc.execute(sql);
        store = new JdbcCaptchaStore(jdbc, new DataSourceTransactionManager(ds), clock);
        service = new BizCaptchaServiceImpl(store, new TianaiSliderEngine(), json, true, EmailOtpService::normalize);
    }

    ImageCaptchaTrack solution(BizCaptchaService.Challenge challenge) {
        String data = jdbc.queryForObject("SELECT payload FROM sys_auth_captcha_state WHERE state_key=?",
                String.class, JdbcCaptchaStore.hash(challenge.challengeId()));
        var state = json.readTree(data);
        int target = Math.round(state.path("validData").path("percentage").floatValue()
                * challenge.backgroundImageWidth());
        return track(challenge, target);
    }

    static ImageCaptchaTrack track(BizCaptchaService.Challenge c, int x) {
        var track = new ImageCaptchaTrack();
        track.setBgImageWidth(c.backgroundImageWidth()); track.setBgImageHeight(c.backgroundImageHeight());
        track.setTemplateImageWidth(c.templateImageWidth()); track.setTemplateImageHeight(c.templateImageHeight());
        track.setLeft(x); track.setTop(0); track.setStartTime(1_000L); track.setStopTime(1_800L);
        var points = new ArrayList<ImageCaptchaTrack.Track>();
        for (int i = 0; i <= 20; i++) {
            points.add(new ImageCaptchaTrack.Track(x * i / 20f, (float) (Math.sin(i) * 2), i * 40f,
                    i == 0 ? "DOWN" : i == 20 ? "UP" : "MOVE"));
        }
        track.setTrackList(points);
        return track;
    }

    @Test void realEngineMatchesAndProofIsCanonicalScopedAndSingleUse() {
        var c = service.issue(BizCaptchaService.Action.LOGIN, " User@Example.test ", "1.1.1.1");
        assertThat(c.backgroundImage()).startsWith("data:image/png;base64,");
        assertThat(c.templateImageHeight()).isEqualTo(c.backgroundImageHeight());
        assertThat(json.writeValueAsString(c)).doesNotContain("randomX", "percentage", "validData");
        var p = service.verify(c.challengeId(), BizCaptchaService.Action.LOGIN, "user@example.test", solution(c), "1.1.1.1");
        service.require(BizCaptchaService.Action.LOGIN, "USER@EXAMPLE.TEST", p.captchaToken());
        assertThatThrownBy(() -> service.require(BizCaptchaService.Action.LOGIN, "user@example.test", p.captchaToken()))
                .isInstanceOf(BizException.class);
    }

    @Test void missingWrongExpiredAndMismatchedProofsAreRejected() {
        assertThatThrownBy(() -> service.require(BizCaptchaService.Action.LOGIN, "a", null)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.require(BizCaptchaService.Action.LOGIN, "a", "wrong")).isInstanceOf(BizException.class);
        var c = service.issue(BizCaptchaService.Action.LOGIN, "a", "1");
        var p = service.verify(c.challengeId(), BizCaptchaService.Action.LOGIN, "a", solution(c), "1");
        assertThatThrownBy(() -> service.require(BizCaptchaService.Action.OTP_SEND, "a", p.captchaToken())).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.require(BizCaptchaService.Action.LOGIN, "a", p.captchaToken())).isInstanceOf(BizException.class);
        var differentPrincipal = service.issue(BizCaptchaService.Action.LOGIN, "a", "1");
        var scoped = service.verify(differentPrincipal.challengeId(), BizCaptchaService.Action.LOGIN, "a", solution(differentPrincipal), "1");
        assertThatThrownBy(() -> service.require(BizCaptchaService.Action.LOGIN, "b", scoped.captchaToken())).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.require(BizCaptchaService.Action.LOGIN, "a", scoped.captchaToken())).isInstanceOf(BizException.class);
        c = service.issue(BizCaptchaService.Action.LOGIN, "a", "1");
        var expired = service.verify(c.challengeId(), BizCaptchaService.Action.LOGIN, "a", solution(c), "1");
        clock.advance(61);
        assertThatThrownBy(() -> service.require(BizCaptchaService.Action.LOGIN, "a", expired.captchaToken())).isInstanceOf(BizException.class);
    }

    @Test void invalidOffsetDimensionAndChallengeReplayCannotMintProof() {
        var c = service.issue(BizCaptchaService.Action.OTP_SEND, "13800000000", "2");
        assertThatThrownBy(() -> service.verify(c.challengeId(), BizCaptchaService.Action.OTP_SEND,
                "13800000000", track(c, 0), "2")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.verify(c.challengeId(), BizCaptchaService.Action.OTP_SEND,
                "13800000000", solutionTrack(c), "2")).isInstanceOf(BizException.class);
        var d = service.issue(BizCaptchaService.Action.LOGIN, "b", "2");
        var spoof = solution(d); spoof.setBgImageWidth(d.backgroundImageWidth() * 2);
        assertThatThrownBy(() -> service.verify(d.challengeId(), BizCaptchaService.Action.LOGIN, "b", spoof, "2"))
                .isInstanceOf(BizException.class);
    }

    ImageCaptchaTrack solutionTrack(BizCaptchaService.Challenge c) { return track(c, 100); }

    @Test void expiredOrPrincipalMismatchedChallengesAreConsumed() {
        var c = service.issue(BizCaptchaService.Action.LOGIN, "a", "3");
        var track = solution(c); clock.advance(121);
        assertThatThrownBy(() -> service.verify(c.challengeId(), BizCaptchaService.Action.LOGIN, "a", track, "3"))
                .isInstanceOf(BizException.class);
        var d = service.issue(BizCaptchaService.Action.LOGIN, "a", "3");
        var dtrack = solution(d);
        assertThatThrownBy(() -> service.verify(d.challengeId(), BizCaptchaService.Action.LOGIN, "b", dtrack, "3"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.verify(d.challengeId(), BizCaptchaService.Action.LOGIN, "a", dtrack, "3"))
                .isInstanceOf(BizException.class);
    }

    @Test void principalAndIpIssueAndVerifyLimitsAreShared() {
        for (int i = 0; i < 10; i++) service.issue(BizCaptchaService.Action.LOGIN, "a", "4");
        assertThatThrownBy(() -> service.issue(BizCaptchaService.Action.LOGIN, "a", "4")).isInstanceOf(BizException.class);
        for (int i = 0; i < 20; i++) {
            final int n = i;
            assertThatThrownBy(() -> service.verify("absent" + n, BizCaptchaService.Action.LOGIN, "b", null, "5"))
                    .isInstanceOf(BizException.class);
        }
        assertThatThrownBy(() -> service.verify("absent", BizCaptchaService.Action.LOGIN, "b", null, "5"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.errorCode().name()).isEqualTo("TOO_MANY_REQUESTS"));
        clock.advance(61);
        assertThatCode(() -> service.issue(BizCaptchaService.Action.LOGIN, "a", "4")).doesNotThrowAnyException();
    }

    @Test void sharedStoreAtomicallyConsumesProofAcrossInstances() throws Exception {
        var c = service.issue(BizCaptchaService.Action.LOGIN, "a", "6");
        var proof = service.verify(c.challengeId(), BizCaptchaService.Action.LOGIN, "a", solution(c), "6");
        var independentStore = new JdbcCaptchaStore(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()), clock);
        var other = new BizCaptchaServiceImpl(independentStore, new TianaiSliderEngine(), json, true, EmailOtpService::normalize);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (var s : List.of(service, other)) results.add(pool.submit(() -> {
                gate.await();
                try { s.require(BizCaptchaService.Action.LOGIN, "a", proof.captchaToken()); return true; }
                catch (BizException e) { return false; }
            }));
            gate.countDown();
            assertThat(results.get(0).get() ^ results.get(1).get()).isTrue();
        }
    }

    @Test void rolloutDisabledAllowsOldClients() {
        var off = new BizCaptchaServiceImpl(store, new TianaiSliderEngine(), json, false, EmailOtpService::normalize);
        assertThatCode(() -> off.require(BizCaptchaService.Action.LOGIN, "a", null)).doesNotThrowAnyException();
    }

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-13T00:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}

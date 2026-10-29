package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.DiningIdentityContracts.DeviceSessionReq;
import ai.neargo.shop.food.dto.DiningIdentityContracts.RegisterDeviceReq;
import ai.neargo.shop.food.dto.DiningIdentityContracts.VersionReq;
import ai.neargo.shop.food.dto.OfflineDraftContracts.ChallengeRequest;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Enroll;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Envelope;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Lease;
import ai.neargo.shop.food.dto.OfflineDraftContracts.LeaseProof;
import ai.neargo.shop.food.dto.OfflineDraftContracts.LeaseRequest;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Replay;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Signer;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Upload;
import ai.neargo.shop.food.dto.OrderLineInput;
import ai.neargo.shop.food.policy.OfflineDraftSignatures;
import ai.neargo.shop.food.service.CheckCartService;
import ai.neargo.shop.food.service.DiningIdentityService;
import ai.neargo.shop.food.service.OfflineDraftService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Real native identity/cart owners and V31 persistence. Conditional on the original disposable
 * native database opt-in, using the same food/food-commerce/commerce embedded profile combination
 * as the native ChannelVertical fixture. No owner or stock port is replaced. This is server draft
 * capture/replay, never evidence of a device database, offline sale, physical kitchen execution or
 * payment.
 */
@TestPropertySource(
        properties = {
            "shop.seed.enabled=true", "shop.food.offline-drafts.enabled=true",
            "shop.food.menu-publication-enabled=true", "shop.food.recipe-publication-enabled=true",
            "shop.food.station-publication-enabled=true",
                    "shop.inventory.stock-authority=INVENTORY",
            "shop.services.internal-token=food-native-only",
                    "shop.outbox.sources.platform.mode=UNIFIED",
            "shop.outbox.sources.inventory.mode=UNIFIED"
        })
class FoodOfflineDraftNativeIT extends FoodEmbeddedStartupIT {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired DiningIdentityService identities;
    @Autowired CheckCartService carts;
    @Autowired OfflineDraftService offline;
    static final String ENTITY = "M0001", STORE = FoodNonPaymentNativeHttpIT.STORE;

    @Override
    @Test
    void nativeMigrationsAndRealHostStartTogether() {
        assertThat(
                        sql.queryForObject(
                                "SELECT MAX(CAST(version AS UNSIGNED)) FROM fnb_flyway_history"
                                    + " WHERE success=1",
                                Integer.class))
                .isGreaterThanOrEqualTo(31);
        assertThat(org.springframework.aop.support.AopUtils.getTargetClass(offline).getName())
                .isEqualTo("ai.neargo.shop.food.service.impl.OfflineDraftServiceImpl");
        assertThat(org.springframework.aop.support.AopUtils.getTargetClass(identities).getName())
                .isEqualTo("ai.neargo.shop.food.service.impl.DiningIdentityServiceImpl");
        assertThat(org.springframework.aop.support.AopUtils.getTargetClass(carts).getName())
                .isEqualTo("ai.neargo.shop.food.service.impl.CheckCartServiceImpl");
    }

    @Test
    void actualDeviceProofFencesSignedDraftsAndReplaysOnlyRealOnlineCarts() throws Exception {
        var menu = new FoodChannelVerticalMenuFixture(context, sql, json, port).publish(null);
        String device = "OD" + key().substring(0, 12);
        var registered =
                identities.registerDevice(
                        ENTITY,
                        STORE,
                        "SF-M0001",
                        new RegisterDeviceReq(device, 0L, "Native offline draft device"),
                        key());
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        var challenge =
                offline.challenge(
                        ENTITY,
                        STORE,
                        device,
                        new ChallengeRequest(publicKey, registered.rawCredential()));
        var stranger = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        assertThatThrownBy(
                        () ->
                                offline.enroll(
                                        ENTITY,
                                        STORE,
                                        device,
                                        new Enroll(
                                                challenge.challengeNo(),
                                                sign(
                                                        stranger,
                                                        "FOOD_OFFLINE_CHALLENGE_V1",
                                                        challenge),
                                                registered.rawCredential())))
                .isInstanceOf(IllegalArgumentException.class);
        var signer =
                offline.enroll(
                        ENTITY,
                        STORE,
                        device,
                        new Enroll(
                                challenge.challengeNo(),
                                sign(pair, "FOOD_OFFLINE_CHALLENGE_V1", challenge),
                                registered.rawCredential()));
        var session =
                identities.createDeviceSession(
                        ENTITY,
                        STORE,
                        device,
                        registered.rawCredential(),
                        new DeviceSessionReq(3600),
                        key());
        var access = new CartAccess(session.party().sessionNo(), session.accessToken(), null);
        assertThatThrownBy(
                        () ->
                                identities.authenticateDeviceSession(
                                        ENTITY,
                                        STORE,
                                        new CartAccess(access.sessionNo(), "forged-token", null)))
                .isInstanceOf(BizException.class);
        var lease =
                lease(device, signer, pair, offline.state(ENTITY, STORE, device, access).fence());
        String item =
                sql.queryForObject(
                        "SELECT i.menu_item_no FROM fnb_menu_item i JOIN fnb_menu_category c"
                                + " ON c.category_no=i.category_no WHERE c.menu_no=?",
                        String.class,
                        menu.number());
        String goods =
                sql.queryForObject(
                        "SELECT goods_no FROM prd_sku WHERE sku_no=? AND market='CN'",
                        String.class,
                        menu.skuNo());
        var line =
                new OrderLineInput(
                        "OFFLINE-LINE",
                        menu.number(),
                        item,
                        menu.revision(),
                        goods,
                        menu.skuNo(),
                        BigDecimal.ONE,
                        null,
                        null,
                        List.of(),
                        List.of());
        var envelope = envelope(device, signer, lease, 1, List.of(line));
        var upload = new Upload(envelope, sign(pair, "FOOD_OFFLINE_DRAFT_V1", envelope));
        int orders = sql.queryForObject("SELECT COUNT(*) FROM ord_order", Integer.class);
        var captured = offline.capture(ENTITY, STORE, device, upload);
        assertThat(captured.status()).isEqualTo("CAPTURED");
        assertThat(captured.cartNo()).isNull();
        assertThat(offline.capture(ENTITY, STORE, device, upload)).isEqualTo(captured);
        var gap = envelope(device, signer, lease, 3, List.of(line));
        assertThat(
                        offline.capture(
                                        ENTITY,
                                        STORE,
                                        device,
                                        new Upload(gap, sign(pair, "FOOD_OFFLINE_DRAFT_V1", gap)))
                                .reason())
                .isEqualTo("SEQUENCE_GAP");
        var changed =
                new OrderLineInput(
                        line.sourceLineRef(),
                        line.menuNo(),
                        line.menuItemNo(),
                        line.menuRevisionNo(),
                        line.goodsNo(),
                        line.skuNo(),
                        new BigDecimal("2"),
                        null,
                        null,
                        List.of(),
                        List.of());
        var conflicting = envelope(device, signer, lease, 1, List.of(changed));
        assertThat(
                        offline.capture(
                                        ENTITY,
                                        STORE,
                                        device,
                                        new Upload(
                                                conflicting,
                                                sign(pair, "FOOD_OFFLINE_DRAFT_V1", conflicting)))
                                .reason())
                .isEqualTo("SEQUENCE_CONTENT_CONFLICT");
        var complete =
                offline.replay(ENTITY, STORE, device, new Replay(captured.draftNo(), access));
        assertThat(complete.status()).isEqualTo("COMPLETE");
        assertThat(complete.appliedLines()).isEqualTo(1);
        assertThat(carts.customerGetCart(complete.cartNo(), access).cart().lines()).hasSize(1);
        assertThat(
                        offline.replay(
                                        ENTITY,
                                        STORE,
                                        device,
                                        new Replay(captured.draftNo(), access))
                                .cartNo())
                .isEqualTo(complete.cartNo());
        var renewed = lease(device, signer, pair, lease.fence());
        assertThat(offline.capture(ENTITY, STORE, device, upload).reason())
                .isEqualTo("EXPIRED_OR_STALE_FENCE");
        // Real owner rejects a genuine but unselected original SKU; first line and its actual cart
        // remain durable.
        String otherGoods =
                sql.queryForObject(
                        "SELECT goods_no FROM prd_sku WHERE sku_no='SK0003' AND market='CN'",
                        String.class);
        var invalid =
                new OrderLineInput(
                        "UNSELECTED",
                        menu.number(),
                        item,
                        menu.revision(),
                        otherGoods,
                        "SK0003",
                        BigDecimal.ONE,
                        null,
                        null,
                        List.of(),
                        List.of());
        var partial = envelope(device, signer, renewed, 1, List.of(line, invalid));
        var partialReceipt =
                offline.capture(
                        ENTITY,
                        STORE,
                        device,
                        new Upload(partial, sign(pair, "FOOD_OFFLINE_DRAFT_V1", partial)));
        var review =
                offline.replay(ENTITY, STORE, device, new Replay(partialReceipt.draftNo(), access));
        assertThat(review.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(review.appliedLines()).isEqualTo(1);
        var nextSession =
                identities.createDeviceSession(
                        ENTITY,
                        STORE,
                        device,
                        registered.rawCredential(),
                        new DeviceSessionReq(3600),
                        key());
        var nextAccess =
                new CartAccess(nextSession.party().sessionNo(), nextSession.accessToken(), null);
        assertThat(
                        offline.replay(
                                        ENTITY,
                                        STORE,
                                        device,
                                        new Replay(partialReceipt.draftNo(), nextAccess))
                                .cartNo())
                .isEqualTo(review.cartNo());
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM ord_order", Integer.class))
                .isEqualTo(orders);
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM fnb_offline_line WHERE draft_no=?",
                                Integer.class,
                                partialReceipt.draftNo()))
                .isEqualTo(1);
        assertThat(
                        json.readValue(
                                sql.queryForObject(
                                        "SELECT payload_json FROM fnb_offline_draft WHERE"
                                            + " draft_no=?",
                                        String.class,
                                        captured.draftNo()),
                                Envelope.class))
                .isEqualTo(envelope);
        var actualDevice = identities.device(ENTITY, STORE, device);
        identities.revokeDevice(
                ENTITY,
                STORE,
                "SF-M0001",
                device,
                new VersionReq(actualDevice.version(), "Native device revoke"),
                key());
        var late = envelope(device, signer, renewed, 2, List.of(line));
        assertThat(
                        offline.capture(
                                        ENTITY,
                                        STORE,
                                        device,
                                        new Upload(late, sign(pair, "FOOD_OFFLINE_DRAFT_V1", late)))
                                .reason())
                .isEqualTo("DEVICE_GENERATION_CHANGED");
    }

    private Lease lease(String device, Signer signer, KeyPair pair, long fence) throws Exception {
        String key = key();
        var proof =
                new LeaseProof(
                        ENTITY,
                        STORE,
                        device,
                        signer.deviceGeneration(),
                        signer.keyNo(),
                        fence,
                        900,
                        key);
        return offline.acquire(
                ENTITY,
                STORE,
                device,
                new LeaseRequest(
                        signer.keyNo(),
                        fence,
                        900,
                        key,
                        sign(pair, "FOOD_OFFLINE_LEASE_V1", proof)));
    }

    private static Envelope envelope(
            String device, Signer signer, Lease lease, long sequence, List<OrderLineInput> lines) {
        return new Envelope(
                ENTITY,
                STORE,
                device,
                signer.deviceGeneration(),
                signer.keyNo(),
                lease.leaseNo(),
                lease.fence(),
                sequence,
                "PICKUP",
                lines);
    }

    private static String sign(KeyPair pair, String domain, Object value) throws Exception {
        var signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(OfflineDraftSignatures.message(domain, value));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}

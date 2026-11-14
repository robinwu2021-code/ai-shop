package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.CheckoutPort;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort;
import ai.neargo.shop.trade.dto.OrderVO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Honest declarations, funded compatibility and canonical current C owner use actual shared
 * originals.
 */
@EnabledIfSystemProperty(named = "beauty.manual.source.test.native", matches = "true")
class ManualSourceBillIT extends ManualSourceHostFixture {
    @MockitoSpyBean(name = "workOrderQuery")
    Object detailQuery;

    @Test
    void manualDeclarationIsNotPayVerification() throws Exception {
        String work = open(Map.of("memberNo", member), "bill");
        var original =
                admittedCounter(
                        work,
                        new ManualServiceAdmissionPort.CounterCustomerRef(
                                "USER", member, null, null),
                        "bill");
        bind(original, "bill");
        var bill =
                successful(
                        http(
                                "GET",
                                "/biz/x/beauty/work-orders/" + work + "/bill",
                                null,
                                null,
                                false));
        assertThat(bill.path("complete").asBoolean()).isTrue();
        var source = bill.path("sources").get(0);
        assertThat(source.path("manualDeclaredMinor").asLong()).isEqualTo(101);
        assertThat(source.path("financialVerification").asText())
                .isEqualTo("NOT_APPLICABLE_MANUAL");
        assertThat(source.path("receipts").isEmpty()).isTrue();
        for (String field :
                List.of("receivedMinor", "refundedMinor", "netReceivedMinor", "outstandingMinor"))
            assertThat(source.path(field).isNull()).as(field).isTrue();
        assertThat(source.path("manualReceipt").path("provenance").asText())
                .isEqualTo("MANUAL_DECLARATION");
        assertThat(source.path("manualReceipt").toString())
                .doesNotContain("accountNo", "claimToken", "note", "contact");
        assertThat(source.path("observation").path("canStart").asBoolean(true)).isFalse();
        OrderVO order = asBuyer(() -> orderOwner.detail(original.source().subOrderNo(), null));
        assertThat(order.manualReceipt()).isNotNull();
        assertThat(order.manualReceipt().declarationNo())
                .isEqualTo(source.path("manualReceipt").path("declarationNo").asText());
        assertThat(order.manualReceipt().financialVerification())
                .isEqualTo("NOT_APPLICABLE_MANUAL");
        assertThat(asBuyer(() -> orderOwner.payResult(original.source().orderNo())).manualReceipt())
                .isEqualTo(order.manualReceipt());
        for (var method : OrderVO.class.getMethods()) {
            if (!method.getName().startsWith("with") || method.getReturnType() != OrderVO.class)
                continue;
            Object[] values =
                    Arrays.stream(method.getParameterTypes())
                            .map(
                                    t ->
                                            t == OrderVO.class
                                                    ? order
                                                    : t == OrderVO.ManualReceiptSummary.class
                                                            ? order.manualReceipt()
                                                            : t == boolean.class
                                                                    ? false
                                                                    : t == int.class
                                                                            ? 1
                                                                            : t == List.class
                                                                                    ? List.of()
                                                                                    : null)
                            .toArray();
            assertThat(((OrderVO) method.invoke(order, values)).manualReceipt())
                    .as(method.getName())
                    .isEqualTo(order.manualReceipt());
        }
        doThrow(BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE))
                .when(manual)
                .source(any(), any());
        var unknown =
                successful(
                        http(
                                "GET",
                                "/biz/x/beauty/work-orders/" + work + "/bill",
                                null,
                                null,
                                false));
        assertThat(unknown.path("complete").asBoolean(true)).isFalse();
        assertThat(unknown.path("sources").get(0).path("manualDeclaredMinor").isNull()).isTrue();
        assertThat(unknown.toString()).doesNotContain("claimToken", "accountNo");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_checkout_payment_request WHERE"
                                        + " operation_no=?",
                                Integer.class,
                                original.source().operation().operationNo()))
                .isZero();
    }

    @Test
    void mixedSourceCurrencyTotalsRemainSeparate() {
        String work =
                successful(
                                http(
                                        "POST",
                                        "/biz/x/beauty/work-orders",
                                        Map.of(
                                                "memberNo",
                                                member,
                                                "storeNo",
                                                store,
                                                "items",
                                                List.of(Map.of("skuNo", sku, "qty", 2))),
                                        "mixed-open-" + sku,
                                        false))
                        .path("workOrderNo")
                        .asText();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM bty_service_task WHERE work_order_no=? AND"
                                        + " qty=1 AND uom='COUNT'",
                                Integer.class,
                                work))
                .isEqualTo(2);
        var original =
                admittedCounter(
                        work,
                        new ManualServiceAdmissionPort.CounterCustomerRef(
                                "USER", member, null, null),
                        "mixed");
        bind(original, "mixed");
        jdbc.update("UPDATE mch_store SET offline_pay_enabled=1 WHERE store_no=?", store);
        jdbc.update(
                "UPDATE prd_goods SET pay_modes='[\"ONLINE\",\"OFFLINE\"]' WHERE goods_no=?",
                goods);
        var request = new HashMap<String, Object>(existingRequest(work, original.source()));
        request.put(
                "source",
                Map.of(
                        "mode",
                        "PREPARE",
                        "customerRef",
                        realConsumerReference(),
                        "payMode",
                        "OFFLINE",
                        "activityChoices",
                        Map.of(),
                        "lines",
                        List.of()));
        var submission =
                successful(
                        http(
                                "POST",
                                "/biz/x/beauty/work-orders/" + work + "/service-submissions",
                                request,
                                "funded-" + sku,
                                false));
        var quote =
                json.readValue(
                        submission.path("quote").toString(), CheckoutPort.CheckoutQuote.class);
        var consent =
                asBuyer(
                        () ->
                                checkout.confirm(
                                        new CheckoutPort.ConfirmCheckoutReq(
                                                quote.operation(),
                                                quote.quoteNo(),
                                                quote.quoteRevision(),
                                                quote.quoteHash(),
                                                "funded-consent-" + sku)));
        var created =
                asBuyer(
                        () ->
                                checkout.place(
                                        new CheckoutPort.PlaceCheckoutReq(
                                                quote.operation(),
                                                consent.confirmationNo(),
                                                "funded-place-" + sku)));
        assertThat(created.phase()).isEqualTo("CREATED");
        successful(
                http(
                        "POST",
                        "/biz/checkout/" + quote.operation().operationNo() + "/offline-receipts",
                        Map.of(
                                "method",
                                "CASH",
                                "amountMinor",
                                101L,
                                "currency",
                                "CNY",
                                "receivedAt",
                                Instant.now().truncatedTo(ChronoUnit.MILLIS).toString(),
                                "note",
                                "Actual full cash receipt"),
                        "funded-cash-" + sku,
                        false));
        asMerchant(
                () ->
                        call(
                                bean("ServiceSubmissionService"),
                                "recoverPending",
                                entity,
                                store,
                                actor,
                                submission.path("submissionNo").asText()));
        var bill =
                successful(
                        http(
                                "GET",
                                "/biz/x/beauty/work-orders/" + work + "/bill",
                                null,
                                null,
                                false));
        assertThat(bill.path("sources").size()).isEqualTo(2);
        assertThat(bill.path("complete").asBoolean()).isTrue();
        var total = bill.path("currencies").get(0);
        assertThat(total.path("manualDeclaredMinor").asLong()).isEqualTo(101);
        assertThat(total.path("receivedMinor").asLong()).isEqualTo(101);
        assertThat(total.path("dueMinor").asLong()).isEqualTo(101);
        assertThat(total.path("netReceivedMinor").asLong()).isEqualTo(101);
    }

    @Test
    void claimedMp155RequiresCurrentMemberOwner() {
        var registration = guest("mp");
        String work = open(Map.of("guestNo", registration.guestNo()), "mp");
        var original =
                admittedCounter(
                        work,
                        new ManualServiceAdmissionPort.CounterCustomerRef(
                                "GUEST", null, registration.guestNo(), registration.claimToken()),
                        "mp");
        bind(original, "mp");
        String path = "/mp/x/beauty/work-orders/" + work;
        assertThat(http("GET", path, null, null, true).path("code").asInt()).isNotZero();
        claim(registration);
        var visible = successful(http("GET", path, null, null, true));
        assertThat(visible.toString()).contains(work).doesNotContain(registration.claimToken());
        org.mockito.Mockito.clearInvocations(detailQuery);
        doThrow(BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE))
                .when(guests)
                .currentClaim(any(), any());
        assertThat(http("GET", path, null, null, true).path("code").asInt()).isNotZero();
        org.mockito.Mockito.verifyNoInteractions(detailQuery);
        doCallRealMethod().when(guests).currentClaim(any(), any());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM bty_guest_claim_receipt WHERE entity_no=? AND"
                                        + " store_no=? AND guest_no=? AND user_no=? AND deleted=0",
                                Integer.class,
                                entity,
                                store,
                                registration.guestNo(),
                                buyer))
                .isOne();
        jdbc.update(
                "UPDATE mbr_guest_registration SET deleted=1 WHERE entity_no=? AND store_no=? AND"
                        + " guest_no=?",
                entity,
                store,
                registration.guestNo());
        try {
            assertThat(http("GET", path, null, null, true).path("code").asInt()).isNotZero();
            org.mockito.Mockito.verifyNoInteractions(detailQuery);
        } finally {
            jdbc.update(
                    "UPDATE mbr_guest_registration SET deleted=0 WHERE entity_no=? AND store_no=?"
                            + " AND guest_no=?",
                    entity,
                    store,
                    registration.guestNo());
        }
        String foreign = "foreign-" + buyer;
        jdbc.update(
                "INSERT INTO usr_account(user_no,nickname,status,created_at,updated_at) VALUES"
                        + " (?,'foreign reader','NORMAL',NOW(),NOW())",
                foreign);
        jdbc.update(
                "UPDATE mbr_guest_registration SET claimed_user_no=? WHERE entity_no=? AND"
                        + " store_no=? AND guest_no=?",
                foreign,
                entity,
                store,
                registration.guestNo());
        try {
            assertThat(http("GET", path, null, null, true).path("code").asInt()).isNotZero();
            org.mockito.Mockito.verifyNoInteractions(detailQuery);
        } finally {
            jdbc.update(
                    "UPDATE mbr_guest_registration SET claimed_user_no=? WHERE entity_no=? AND"
                            + " store_no=? AND guest_no=?",
                    buyer,
                    entity,
                    store,
                    registration.guestNo());
        }
        jdbc.update("UPDATE usr_account SET status='DISABLED' WHERE user_no=?", buyer);
        try {
            assertThat(http("GET", path, null, null, true).path("code").asInt()).isNotZero();
        } finally {
            jdbc.update("UPDATE usr_account SET status='NORMAL' WHERE user_no=?", buyer);
        }
        successful(http("GET", path, null, null, true));
        assertThat(observationRow(original.source().subOrderNo()))
                .containsEntry("customer_kind", "GUEST")
                .containsEntry("guest_no", registration.guestNo())
                .containsEntry("buyer_no", null);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT identity_kind FROM bty_work_order_context WHERE"
                                        + " work_order_no=?",
                                String.class,
                                work))
                .isEqualTo("GUEST");
    }
}

package ai.neargo.shop.payclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.merchant.dto.PaymentApplymentVO;
import ai.neargo.shop.pay.client.PayBizInternalApi.EntityChannelAccount;
import ai.neargo.shop.pay.client.PayBizInternalApi.EntityChannelAccounts;
import java.util.List;
import org.junit.jupiter.api.Test;

/** TDD-门店代码与商户渠道展示 C1、C2、C4：App 主体页渠道列表的来源与每行字段。 */
class MerchantChannelListingTest {
    final BizPaymentAppService pay = mock(BizPaymentAppService.class);
    final MerchantChannelListing listing = new MerchantChannelListing(pay);
    final List<PaymentApplymentVO> local = List.of(new PaymentApplymentVO("WECHAT", "微信", "APPLYING", false, null, null, null,
            null, null, List.of(), true, null, null, null));

    @Test
    void anEntityWithAPaymentMerchantListsItsPayChannelAccounts() {
        when(pay.entityChannelAccounts("MUME1")).thenReturn(new EntityChannelAccounts("MUME1", "PM1", List.of(
                new EntityChannelAccount("PM-A", "LINEPAY", "LINE Pay", false, "ENABLED", "1626862643", "ACTIVE", "VERIFIED", null,
                        List.of("STUME1")),
                new EntityChannelAccount("PM-B", "NEWEBPAY", "藍新金流", false, "ENABLED", "MS354053380", "UNVERIFIED", "PENDING",
                        "ACCOUNT_UNVERIFIED", List.of()),
                new EntityChannelAccount("PM-C", "GAMAPAY", "橘子支付", false, "PENDING", "350421", "UNVERIFIED", "PENDING",
                        "CHANNEL_NOT_LIVE", List.of()),
                new EntityChannelAccount("PM-D", "LINEPAY_TEST", "LINE Pay 測試", true, "ENABLED", "1581000931", "ACTIVE", "VERIFIED",
                        null, List.of()))));

        var rows = listing.list("MUME1", () -> local);

        assertThat(rows).extracting(PaymentApplymentVO::payChannel).containsExactly("LINEPAY", "NEWEBPAY", "GAMAPAY", "LINEPAY_TEST");
        var live = rows.get(0);
        assertThat(live.applyStatus()).isEqualTo("ACTIVE");
        assertThat(live.canReceiveMoney()).isTrue();
        assertThat(live.channelMerchantId()).as("C1 全号").isEqualTo("1626862643");
        assertThat(live.enabledStores()).extracting(PaymentApplymentVO.EnabledStore::storeNo).containsExactly("STUME1");
        assertThat(rows.get(1).applyStatus()).as("C2 已开通渠道、账户未核验").isEqualTo("PENDING_ACTIVATION");
        assertThat(rows.get(1).pendingReason()).isEqualTo("ACCOUNT_UNVERIFIED");
        assertThat(rows.get(2).applyStatus()).as("C2 渠道待开通").isEqualTo("PENDING_ACTIVATION");
        assertThat(rows.get(2).channelStatus()).isEqualTo("PENDING");
        assertThat(rows.get(2).canReceiveMoney()).isFalse();
        assertThat(rows.get(3).sandbox()).isTrue();
        assertThat(rows).allSatisfy(r -> assertThat(r.storeNo()).as("主体级行，不被门店过滤掉").isNull());
    }

    @Test
    void anEntityWithoutAPaymentMerchantKeepsTheLocalApplymentList() {
        when(pay.entityChannelAccounts("M1")).thenReturn(new EntityChannelAccounts("M1", null, List.of()));
        assertThat(listing.list("M1", () -> local)).as("C4").isSameAs(local);
        assertThat(local.getFirst().channelMerchantId()).as("本地行没有渠道商户号").isNull();
    }
}

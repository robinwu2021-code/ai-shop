package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.commercebridge.FoundationPaymentAccess;
import ai.neargo.shop.merchant.paysync.StoreSyncProperties;
import ai.neargo.shop.payclient.*;
import ai.neargo.svc.client.ServiceCallException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.LinkedHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Host finance target and trusted source mapping precede the independent PAY typed authorization. */
@RestController
@Profile("api")
@ConditionalOnProperty(name="shop.pay.channel-account-claims.enabled",havingValue="true")
@RequestMapping("/biz/pay/v2/channel-account-claims")
public class BizChannelAccountClaimController {
    private final PayChannelAccountClaimForwarder forwarder;
    private final FoundationPaymentAccess access;
    private final ObjectProvider<PaySourceLinkApi> sources;
    private final StoreSyncProperties sync;
    public BizChannelAccountClaimController(PayChannelAccountClaimForwarder forwarder,FoundationPaymentAccess access,
            ObjectProvider<PaySourceLinkApi> sources,StoreSyncProperties sync) {
        this.forwarder=forwarder;this.access=access;this.sources=sources;this.sync=sync;
    }
    @GetMapping({"","/by-request/{requestNo}","/{claimNo}","/{claimNo}/files","/{claimNo}/files/{fileNo}","/{claimNo}/commands/{requestNo}"})
    @PreAuthorize("@perm.canBiz('biz:finance')")
    public ResponseEntity<byte[]> read(HttpServletRequest request) throws IOException {return relay(request);}
    @PostMapping({"","/{claimNo}/files","/{claimNo}/submit"})
    @PreAuthorize("@perm.canBiz('biz:finance')")
    public ResponseEntity<byte[]> write(HttpServletRequest request) throws IOException {return relay(request);}

    private ResponseEntity<byte[]> relay(HttpServletRequest request) throws IOException {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        String target=path+(request.getQueryString()==null?"":"?"+request.getQueryString());
        if(!PayChannelAccountClaimForwarder.allowed(request.getMethod(),target))throw new PayBizException(HttpStatus.BAD_REQUEST);
        var selected=Collections.list(request.getHeaders("X-Store-No"));
        if(selected.isEmpty()||selected.stream().anyMatch(s->s==null||s.isBlank()))throw new PayBizException(HttpStatus.FORBIDDEN);
        var foundation=access.require(request,BizContext.requireMerchantNo(),BizContext.requireStoreNo());
        var merchants=Collections.list(request.getHeaders("X-Merchant-No"));
        if(merchants.size()!=1||merchants.getFirst().isBlank())throw new PayBizException(HttpStatus.FORBIDDEN);
        var api=sources.getIfAvailable();if(api==null)throw new PayBizException(HttpStatus.SERVICE_UNAVAILABLE);
        PaySourceLinkApi.Status mapping;
        try {mapping=api.status(sync.sourceScope(),foundation.entityNo());}
        catch(ServiceCallException unavailable){throw new PayBizException(HttpStatus.SERVICE_UNAVAILABLE);}
        if(mapping==null||mapping.status()==null)throw new PayBizException(HttpStatus.SERVICE_UNAVAILABLE);
        if("NONE".equals(mapping.status()))throw new PayBizException(HttpStatus.CONFLICT);
        if(!java.util.Set.of("PENDING","NEED_MORE","REJECTED","APPROVED").contains(mapping.status()))
            throw new PayBizException(HttpStatus.SERVICE_UNAVAILABLE);
        if(mapping.merchantRef()==null||mapping.merchantRef().isBlank())throw new PayBizException(HttpStatus.SERVICE_UNAVAILABLE);
        if(!mapping.merchantRef().equals(merchants.getFirst()))throw new PayBizException(HttpStatus.FORBIDDEN);
        byte[] bytes=PayBizRequestBody.read(request,"POST".equals(request.getMethod())&&path.endsWith("/files"));
        var headers=new LinkedHashMap<String,String>();
        for(String name:Collections.list(request.getHeaderNames()))headers.put(name,request.getHeader(name));
        try {
            var result=forwarder.forward(request.getMethod(),target,headers,bytes);
            var responseHeaders=new HttpHeaders();result.headers().forEach(responseHeaders::put);
            return ResponseEntity.status(result.status()).headers(responseHeaders).body(result.body());
        } catch(HttpTimeoutException timeout){throw new PayBizException(HttpStatus.GATEWAY_TIMEOUT);}
        catch(IOException unavailable){throw new PayBizException(HttpStatus.BAD_GATEWAY);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new PayBizException(HttpStatus.BAD_GATEWAY);}
    }
}

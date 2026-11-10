package ai.neargo.shop.payclient;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Exact N2 transport. User credentials/bytes only; no host claim state or service identity. */
public final class PayChannelAccountClaimForwarder {
    public static final String BASE="/biz/pay/v2/channel-account-claims";
    private final String origin;
    private final HttpClient http;
    public PayChannelAccountClaimForwarder(URI origin) {
        String host=origin.getHost();boolean local=host!=null&&Set.of("127.0.0.1","localhost","[::1]").contains(host);
        if(host==null||origin.getUserInfo()!=null||origin.getQuery()!=null||origin.getFragment()!=null
                ||!(origin.getPath().isEmpty()||origin.getPath().equals("/"))
                ||!("https".equals(origin.getScheme())||local&&"http".equals(origin.getScheme())))
            throw new IllegalArgumentException("near-pay origin required");
        this.origin=origin.toString().replaceAll("/$","");
        http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public record Response(int status,Map<String,List<String>> headers,byte[] body) {
        @Override public String toString(){return "ChannelAccountClaimResponse[status="+status+"]";}
    }
    public Response forward(String method,String target,Map<String,String> headers,byte[] body) throws IOException,InterruptedException {
        if(!allowed(method,target)) throw new IllegalArgumentException("unsupported channel account claim route");
        boolean upload="POST".equals(method)&&URI.create(target).getPath().endsWith("/files");
        if(body==null||body.length>(upload?6*1024*1024:1024*1024)) throw new IllegalArgumentException("BIZ request exceeds limit");
        var request=HttpRequest.newBuilder(URI.create(origin+target)).timeout(Duration.ofSeconds(20))
                .method(method,HttpRequest.BodyPublishers.ofByteArray(body));
        Set<String> input=Set.of("authorization","x-merchant-no","x-store-no","x-request-id","x-trace-id","accept","accept-language","content-type");
        headers.forEach((name,value)->{if(name!=null&&value!=null&&input.contains(name.toLowerCase(Locale.ROOT)))request.header(name,value);});
        var response=http.send(request.build(),HttpResponse.BodyHandlers.ofByteArray());
        var out=new LinkedHashMap<String,List<String>>();
        Set<String> output=Set.of("content-type","content-disposition","cache-control","pragma","x-content-type-options","content-security-policy","x-frame-options","x-request-id");
        response.headers().map().forEach((name,value)->{if(output.contains(name))out.put(name,List.copyOf(value));});
        return new Response(response.statusCode(),Map.copyOf(out),response.body());
    }
    public static boolean allowed(String method,String target) {
        if(method==null||target==null||target.contains("%")||target.contains("\\")||target.contains("..")||target.contains("#"))return false;
        URI uri;try{uri=URI.create(target);}catch(IllegalArgumentException invalid){return false;}
        if(uri.isAbsolute()||uri.getAuthority()!=null)return false;
        String path=uri.getPath(),id="[A-Za-z0-9_-]{1,64}",root=java.util.regex.Pattern.quote(BASE);
        if("GET".equals(method))return path.matches(root+"(?:/by-request/"+id+"|/"+id+"(?:/commands/"+id+"|/files(?:/[a-f0-9]{32})?)?)?");
        return "POST".equals(method)&&(path.equals(BASE)||path.matches(root+"/"+id+"/(?:files|submit)"));
    }
}

package ai.neargo.shop.shell;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.ViewGroup;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

/**
 * C 端的 WebView 壳。
 *
 * <p><b>这不是 uni-app 的原生打包</b>：原生打包要 DCloud appid + HBuilderX（GUI）
 * 或离线 SDK，这里都拿不到。所以走 H5 构建产物 + WebView 的路子 ——
 * 页面、路由、样式与真机一致。原生能力按需在壳里补：<b>推送已接</b>（个推原生 SDK +
 * {@link PushBridge} JS 桥，见 ShellApplication/PushIntentService）；微信/支付宝支付、
 * 扫码仍未接。用来看界面、走流程、验推送够，联调支付不够。
 *
 * <p>资源来自 {@code assets/h5/}，由 {@code npm run build:h5} 产出并拷入。
 * 构建时用 {@code H5_BASE=./}，否则资源引用是绝对路径，{@code file://} 下全 404。
 */
public class MainActivity extends AppCompatActivity {

    /**
     * 页面入口。**按构建类型分**（`app/build.gradle` 里的 `resValue`）：
     *
     * <ul>
     *   <li><b>release</b>：{@code file:///android_asset/h5/index.html} —— 离线可用，
     *       装了就能看，不依赖任何机器开着；</li>
     *   <li><b>debug</b>：{@code http://localhost:5174} —— 连宿主机的 dev server。</li>
     * </ul>
     *
     * <p><b>debug 为什么必须走 http 而不是 file://</b>：`file://` 下页面的 origin 是
     * {@code null}，后端的 CORS 白名单不认它 —— <b>每个请求都被浏览器拦在发出之前</b>，
     * 表现是各页渲染成空状态（不是报错，是「看起来这个商家什么都没有」）。
     * 配 {@code adb reverse tcp:5174 tcp:5174} 与 {@code tcp:8081} 之后，
     * 设备上的 {@code localhost} 指向宿主机，而 {@code http://localhost:5174}
     * 正是后端白名单里已有的那一条，后端一行都不用改。
     *
     * <p>真机上的 uni-app 原生包不吃这一套（原生请求没有 CORS）——
     * 这是**这个壳特有的**限制，别把它当成产品缺陷去改后端。
     */
    private static final String ENTRY_RES = "shell_entry";

    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        // uni-app 的 H5 产物用 localStorage 存登录态、皮肤、mock 数据库
        s.setDomStorageEnabled(true);
        // file:// 下加载同目录的 js/css。
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        // release 版：H5 从 assets（file://）加载，但要调 http://<服务器IP>:8081 的 API ——
        // 跨源。开这个让 file:// 页面能请求其它源（否则 XHR/fetch 被同源策略挡下，页面空转）。
        // 本壳只加载我们自己打进 assets 的 H5，不加载外部页面，风险可控。
        s.setAllowUniversalAccessFromFileURLs(true);
        // 页面里有 Google Fonts 的外链，混合内容要允许，否则字体请求被拦
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // 按设备宽度渲染，不然会以 980px 桌面宽度缩放
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);

        // 原生推送桥：H5 里 window.NativePush.getClientId() 拿本机个推 cid，
        // 再走 /biz/push-token 注册。cid 由个推异步回调写入 PushBridge（见 PushIntentService）。
        web.addJavascriptInterface(new PushBridge(), "NativePush");

        // 站内跳转留在 WebView 里，不要甩给系统浏览器
        web.setWebViewClient(new WebViewClient());

        // 把页面的 console 捞到 logcat。**没有它，WebView 里的 JS 报错是完全不可见的** ——
        // 页面白屏时看不出是资源 404、脚本异常，还是业务逻辑走了空分支
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i("ShellConsole", m.messageLevel() + " " + m.message()
                        + " @" + m.sourceId() + ":" + m.lineNumber());
                return true;
            }
        });

        // 系统返回键当作页面后退 —— 不接管的话按一下就退出 app，
        // 而 uni-app 是单页路由，用户会以为应用崩了
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (web.canGoBack()) {
                    web.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        String entry = getString(getResources().getIdentifier(
                ENTRY_RES, "string", getPackageName()));
        Log.i("ShellConsole", "entry = " + entry);
        web.loadUrl(entry);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
        }
        super.onDestroy();
    }
}

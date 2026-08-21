package ai.neargo.shop.shell;

import android.webkit.JavascriptInterface;

/**
 * 原生 ↔ H5 的推送桥。通过 {@code addJavascriptInterface(new PushBridge(), "NativePush")}
 * 暴露给 WebView，H5 里 {@code window.NativePush.getClientId()} 即可拿到本机 cid。
 *
 * <p><b>cid 是异步来的</b>（个推初始化后约 2~3s 由 {@link PushIntentService#onReceiveClientId}
 * 回调），所以这里用静态字段暂存，H5 侧轮询读取即可 —— 比原生反向 push 事件到 JS 更简单可靠，
 * 也不受「回调早于页面加载」的时序影响。
 */
public final class PushBridge {

    private static volatile String clientId = "";
    private static volatile boolean online = false;

    /** 供 {@link PushIntentService} 回调时写入。 */
    static void setClientId(String cid) {
        if (cid != null && !cid.isEmpty()) {
            clientId = cid;
        }
    }

    static void setOnline(boolean o) {
        online = o;
    }

    /** H5 读取本机 cid；还没拿到时为空串。 */
    @JavascriptInterface
    public String getClientId() {
        return clientId;
    }

    /** 供应商恒为个推（uni-push 底座即个推）。与后端 PushProvider 逐字一致。 */
    @JavascriptInterface
    public String getProvider() {
        return "GETUI";
    }

    /** 设备在个推的在线态。仅供调试展示，注册不依赖它。 */
    @JavascriptInterface
    public boolean isOnline() {
        return online;
    }
}

package ai.neargo.shop.shell;

import android.app.Application;

import com.igexin.sdk.PushManager;

/**
 * 应用入口：初始化个推 SDK。
 *
 * <p>放在 Application.onCreate 而不是 Activity —— 个推要在进程一起来就初始化，
 * 才能在 app 未打开时也被服务唤醒接收离线消息。cid 通过 {@link PushIntentService}
 * 的 onReceiveClientId 回调拿到，存进 {@link PushBridge} 供 WebView 里的 H5 读取。
 *
 * <p><b>只做 preInit + initialize</b>：个推 demo 里那套 startAuth（AuthInteractor）是
 * demo 自己「从 app 直接调 restapi 发测试推」用的，需要 appKey/masterSecret 落到端上。
 * 我们的推送由后端发，端上只需注册收消息，故不引入，也不把密钥带进 app。
 */
public class ShellApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        PushManager.getInstance().preInit(this);
        PushManager.getInstance().initialize(this);
    }
}

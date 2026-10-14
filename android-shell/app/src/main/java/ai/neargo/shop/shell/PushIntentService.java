package ai.neargo.shop.shell;

import android.content.Context;
import android.util.Log;

import com.igexin.sdk.GTIntentService;
import com.igexin.sdk.message.GTCmdMessage;
import com.igexin.sdk.message.GTNotificationMessage;
import com.igexin.sdk.message.GTTransmitMessage;

/**
 * 个推消息回调（在子线程回调；必须在 AndroidManifest 声明才会被 SDK 绑定）。
 *
 * <ul>
 *   <li>{@code onReceiveClientId} —— 本机 cid，存进 {@link PushBridge} 供 H5 注册用</li>
 *   <li>{@code onNotificationMessageArrived/Clicked} —— 通知栏消息由 SDK 自动弹出，
 *       这里只留痕；点击的深链路由（payload.link → WebView 跳页）后续再接</li>
 *   <li>{@code onReceiveMessageData} —— 透传消息（本项目后端发的是通知类，这里备用）</li>
 * </ul>
 */
public class PushIntentService extends GTIntentService {

    private static final String TAG = "ShellPush";

    @Override
    public void onReceiveClientId(Context context, String clientId) {
        Log.i(TAG, "onReceiveClientId -> " + clientId);
        PushBridge.setClientId(clientId);
    }

    @Override
    public void onReceiveOnlineState(Context context, boolean online) {
        Log.i(TAG, "onReceiveOnlineState -> " + (online ? "online" : "offline"));
        PushBridge.setOnline(online);
    }

    @Override
    public void onReceiveMessageData(Context context, GTTransmitMessage msg) {
        byte[] payload = msg.getPayload();
        Log.i(TAG, "onReceiveMessageData -> " + (payload == null ? "null" : new String(payload)));
    }

    @Override
    public void onNotificationMessageArrived(Context context, GTNotificationMessage message) {
        Log.i(TAG, "notification arrived: " + message.getTitle() + " / " + message.getContent());
    }

    @Override
    public void onNotificationMessageClicked(Context context, GTNotificationMessage message) {
        Log.i(TAG, "notification clicked: " + message.getTitle());
    }

    @Override
    public void onReceiveServicePid(Context context, int pid) {
        Log.d(TAG, "onReceiveServicePid -> " + pid);
    }

    @Override
    public void onReceiveCommandResult(Context context, GTCmdMessage cmdMessage) {
        Log.d(TAG, "onReceiveCommandResult -> " + cmdMessage);
    }
}

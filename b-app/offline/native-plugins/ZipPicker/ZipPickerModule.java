package top.hxmall.bapp.plugin;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import com.alibaba.fastjson.JSONObject;

import io.dcloud.feature.uniapp.annotation.UniJSMethod;
import io.dcloud.feature.uniapp.bridge.UniJSCallback;
import io.dcloud.feature.uniapp.common.UniModule;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * C-AC 商品快速录入：App 端选 .zip 文件的原生插件。
 *
 * uni-app 的 uni.chooseFile 在 App 运行时不存在，所以走 SAF（ACTION_GET_CONTENT）。
 * 选到的是 content:// URI，plus.zip 解不了，这里**复制到应用缓存**再把绝对路径回给 JS。
 * 结果通过 UniModule.onActivityResult 拿（PandoraEntryActivity 会转发给已注册模块）。
 */
public class ZipPickerModule extends UniModule {

    private static final int REQ_PICK_ZIP = 0x5A49; // 'ZI'
    private UniJSCallback pending;

    @UniJSMethod(uiThread = true)
    public void chooseZip(UniJSCallback callback) {
        this.pending = callback;
        Activity act = (Activity) mUniSDKInstance.getContext();
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        act.startActivityForResult(Intent.createChooser(intent, "选择商品压缩包"), REQ_PICK_ZIP);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_PICK_ZIP || pending == null) {
            return;
        }
        UniJSCallback cb = pending;
        pending = null;
        JSONObject res = new JSONObject();
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            res.put("cancel", true);
            cb.invoke(res);
            return;
        }
        InputStream in = null;
        OutputStream out = null;
        try {
            Uri uri = data.getData();
            Activity act = (Activity) mUniSDKInstance.getContext();
            File target = new File(act.getCacheDir(), "goods-import-" + System.currentTimeMillis() + ".zip");
            in = act.getContentResolver().openInputStream(uri);
            out = new FileOutputStream(target);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            res.put("path", target.getAbsolutePath());
            cb.invoke(res);
        } catch (Exception e) {
            res.put("error", e.getMessage() == null ? "读取失败" : e.getMessage());
            cb.invoke(res);
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignore) { }
            try { if (out != null) out.close(); } catch (Exception ignore) { }
        }
    }
}

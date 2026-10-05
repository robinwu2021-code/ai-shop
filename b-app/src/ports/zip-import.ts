// 压缩包导入商品图：选 zip → 解压 → 列文件 → 分类排序（归类逻辑在 @shared/ports/zip-media）。
//
// **解压是 App 原生能力**（plus.zip），小程序/H5 没有，所以这里按端分叉。
// 归类/排序是纯逻辑、已单测（zip-media.test.ts）；这个文件只管「把 zip 变成一堆本地路径」。
import { classifyZip, type ZipMedia } from "@shared/ports/zip-media";

// plus 是 App 运行时注入的全局，类型未在 @dcloudio 里导出，这里按 any 用
declare const plus: any;

/** 选一个 .zip 文件，返回它的本地路径。用户取消时 reject。 */
function pickZip(): Promise<string> {
  return new Promise((resolve, reject) => {
    // uni.chooseFile 在 App 上会调起系统文件选择器；不同机型/系统的返回要真机核。
    // 取消走 fail（errMsg 带 cancel），上层据此静默。
    const api = (uni as unknown as {
      chooseFile?: (o: Record<string, unknown>) => void;
    }).chooseFile;
    if (typeof api !== "function") {
      reject(new Error("当前环境不支持选择文件，压缩包导入请在 App 内使用"));
      return;
    }
    api({
      count: 1,
      extension: [".zip"],
      success: (res: { tempFilePaths?: string[]; tempFiles?: { path: string }[] }) => {
        const path = res.tempFilePaths?.[0] ?? res.tempFiles?.[0]?.path;
        if (path) resolve(path);
        else reject(new Error("没拿到文件"));
      },
      fail: (e: { errMsg?: string }) => reject(
        new Error(e.errMsg?.includes("cancel") ? "已取消" : (e.errMsg || "选择失败")),
      ),
    });
  });
}

/** 把 zip 解压到临时目录，返回里面所有文件的本地路径（递归）。 */
function decompress(zipPath: string): Promise<string[]> {
  return new Promise((resolve, reject) => {
    // #ifdef APP-PLUS
    const target = "_doc/goods-zip-" + Date.now() + "/";
    plus.zip.decompress(
      zipPath,
      target,
      () => plus.io.resolveLocalFileSystemURL(target, (entry: any) => {
        const out: string[] = [];
        const walk = (dir: any, done: () => void) => {
          dir.createReader().readEntries((entries: any[]) => {
            let pending = entries.length;
            if (!pending) return done();
            for (const en of entries) {
              if (en.isDirectory) walk(en, () => { if (--pending === 0) done(); });
              else { out.push(en.fullPath); if (--pending === 0) done(); }
            }
          }, reject);
        };
        walk(entry, () => resolve(out));
      }, reject),
      reject,
    );
    // #endif
    // #ifndef APP-PLUS
    reject(new Error("压缩包导入目前只支持 App"));
    // #endif
  });
}

/** 选 zip → 解压 → 归类。返回的是本地临时路径，交给上层逐张上传。 */
export async function importZipMedia(): Promise<ZipMedia> {
  const zip = await pickZip();
  const paths = await decompress(zip);
  return classifyZip(paths);
}

// 压缩包导入商品图：选 zip → 解压 → 列文件 → 分类排序（归类逻辑在 @shared/ports/zip-media）。
//
// **解压是 App 原生能力**（plus.zip），小程序/H5 没有，所以这里按端分叉。
// 归类/排序是纯逻辑、已单测（zip-media.test.ts）；这个文件只管「把 zip 变成一堆本地路径」。
import { classifyZip, type ZipMedia } from "@shared/ports/zip-media";

// plus 是 App 运行时注入的全局，类型未在 @dcloudio 里导出，这里按 any 用
declare const plus: any;

/** 选一个 .zip 文件，返回它在应用缓存里的绝对路径。用户取消时 reject。 */
function pickZip(): Promise<string> {
  return new Promise((resolve, reject) => {
    // #ifdef APP-PLUS
    // uni.chooseFile 在 App 运行时不存在，走原生插件（SAF 选文件 + 复制到缓存，见
    // 离线工程 ZipPickerModule）。requireNativePlugin 拿不到说明基座没打进这个模块。
    const zp = uni.requireNativePlugin("ZipPicker") as
      | { chooseZip: (cb: (res: { path?: string; cancel?: boolean; error?: string }) => void) => void }
      | undefined;
    if (!zp || typeof zp.chooseZip !== "function") {
      reject(new Error("当前基座不含文件选择插件，请用含 ZipPicker 的包"));
      return;
    }
    zp.chooseZip((res) => {
      if (res.cancel) reject(new Error("已取消"));
      else if (res.path) resolve(res.path);
      else reject(new Error(res.error || "没拿到文件"));
    });
    // #endif
    // #ifndef APP-PLUS
    reject(new Error("压缩包导入目前只支持 App"));
    // #endif
  });
}

/** 把 zip 解压到临时目录，返回里面所有文件的本地路径（递归）。 */
function decompress(zipPath: string): Promise<string[]> {
  return new Promise((resolve, reject) => {
    // #ifdef APP-PLUS
    const target = "_doc/goods-zip-" + Date.now() + "/";
    // 绝对文件路径要加 file:// 前缀，plus.zip 才认（_doc/_www 这类相对 URL 才不用）
    const src = zipPath.startsWith("/") ? "file://" + zipPath : zipPath;
    plus.zip.decompress(
      src,
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

/** 读解压出的 txt 文本内容（商品文字）。App 用 plus.io；读失败返回 undefined。 */
export function readTextFile(path: string): Promise<string | undefined> {
  return new Promise((resolve) => {
    // #ifdef APP-PLUS
    plus.io.resolveLocalFileSystemURL(path, (entry: any) => {
      entry.file((file: any) => {
        const reader = new plus.io.FileReader();
        reader.onloadend = (e: any) => resolve(typeof e.target.result === "string" ? e.target.result : undefined);
        reader.onerror = () => resolve(undefined);
        reader.readAsText(file, "utf-8");
      }, () => resolve(undefined));
    }, () => resolve(undefined));
    // #endif
    // #ifndef APP-PLUS
    resolve(undefined);
    // #endif
  });
}

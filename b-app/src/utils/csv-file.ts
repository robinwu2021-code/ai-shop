import type { ComposerTranslation } from "vue-i18n";

/**
 * CSV 的存盘与选取。**平台差异关在这个文件里** ——
 * 页面与组件不许出现条件编译（见 packages/ui/src/shell.ts 开头那条铁律）。
 *
 * <p>这一组的差异是真实的、也是不可调和的：文件系统这件事在 H5 与小程序上
 * 根本不是同一套 API，而在 App 里又是第三套。与其在页面里分叉三次，
 * 不如让页面只问两个问题：**能不能选文件**、**存不存得下去**。
 */

/**
 * 这个端能不能让用户从磁盘里挑一个文件。
 *
 * <p>只有 H5 能。小程序没有 file input，App 的文件选择要原生权限 ——
 * 而真会用到批量导入的商家（有 ERP 的那些）本来就坐在电脑前。
 * 页面据此决定是显示「选择文件」还是只留「粘贴」。
 */
export const canPickFile: boolean =
  // #ifdef H5
  true;
  // #endif
  // #ifndef H5
  false;
  // #endif

/**
 * 把一段文本存成 CSV 文件。
 *
 * <p>H5 走 Blob + a[download]：**文件名在这里定**，服务端不设 Content-Disposition
 * （那要 HttpServletResponse，而架构守卫禁止领域包碰 web 运行时）。
 *
 * <p>其它端存不了盘 —— 复制到剪贴板并说清楚：他要的是把这份表拿到电脑上，
 * 而一个「导出成功」却找不到文件的提示比什么都不做更糟。
 */
export function saveCsv(text: string, filename: string, t: ComposerTranslation) {
  // #ifdef H5
  const blob = new Blob([text], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  // 立刻 revoke 会让 Safari 的下载半路断掉，给它一会儿
  setTimeout(() => URL.revokeObjectURL(url), 4000);
  // #endif
  // #ifndef H5
  uni.setClipboardData({
    data: text,
    success: () => uni.showToast({ title: t("skuIdentity.copiedToClipboard"), icon: "none" }),
  });
  // #endif
}

/**
 * 让用户挑一个 CSV，读成文本。取消或读失败都回 null。
 *
 * <p><b>按 UTF-8 读，读出替换字符就退回 GBK。</b>中文 Windows 的 Excel
 * 默认存 GBK —— 按 UTF-8 硬读会得到一整列「锟斤拷」，而那看起来像
 * 「导入功能坏了」，不像「编码不对」。
 */
export function pickCsvFile(): Promise<string | null> {
  // #ifdef H5
  return new Promise((resolve) => {
    const input = document.createElement("input");
    input.type = "file";
    input.accept = ".csv,text/csv,text/plain";
    input.onchange = () => {
      const file = input.files && input.files[0];
      if (!file) {
        resolve(null);
        return;
      }
      file.arrayBuffer().then((buf) => {
        const utf8 = new TextDecoder("utf-8").decode(buf);
        // U+FFFD = 解码失败留下的替换字符，出现即说明这份文件不是 UTF-8
        if (utf8.includes("�")) {
          try {
            resolve(new TextDecoder("gbk").decode(buf));
            return;
          } catch {
            /* 这个浏览器不支持 gbk 解码，那就把 UTF-8 那份给他，让他看到乱码好过看到空白 */
          }
        }
        resolve(utf8);
      }).catch(() => resolve(null));
    };
    input.click();
  });
  // #endif
  // #ifndef H5
  return Promise.resolve(null);
  // #endif
}

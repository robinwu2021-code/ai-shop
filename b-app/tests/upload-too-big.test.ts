/**
 * **413 要说清楚是什么太大了。**
 *
 * <p>`uploadFile` 同时在传商品图、证照和**整个压缩包**，而 413 的响应体是空的
 * （容器在进 Controller 之前就拒了），端上这一句是唯一能说话的地方。
 * 此前它写死「图片太大，请换一张小一点的（最大 5MB）」——
 * 2026-10-08 线上实况：商家在商品编辑里导入压缩包，得到的就是这句
 * **与他做的事无关**的话，而真正的原因是容器的 multipart 上限按单图定成了 5MB。
 *
 * <p>所以两头各钉一半：`uploadFile` 不许再替调用方猜，调用方要把话说准。
 */
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const ROOT = resolve(__dirname, "../..");
const read = (p: string) => readFileSync(resolve(ROOT, p), "utf8");

describe("上传超限的提示", () => {
  it("★★★ uploadFile 不替调用方猜「图片」", () => {
    const http = read("packages/shared/src/net/http-client.ts");
    expect(http, "413 的默认文案不该假定传的是图片")
      .toContain('tooBigHint || "这个文件太大了，换一个小一点的"');
    expect(http, "要留一个让调用方说准的参数").toContain("tooBigHint?: string");
  });

  it("★★★ 压缩包导入说的是压缩包，不是图片", () => {
    const api = read("b-app/src/api/http.ts");
    const zip = /mZipImport:[\s\S]{0,260}?\),/.exec(api)?.[0] ?? "";
    expect(zip, "mZipImport 没匹配到").toContain("uploadFile");
    expect(zip).toContain("压缩包太大了");
    expect(zip, "压缩包的提示里不该出现「图」").not.toMatch(/图/);
  });

  it("★★★ 容器的 multipart 上限要装得下一整包，且与 nginx 对齐", () => {
    /*
     * 这一层是粗闸：具体多大算大由控制器自己判（zip 有四道、单图有 MAX_BYTES）。
     * 按单图那个 5MB 设的话，zip 请求在进方法之前就被拒，那四道一道都跑不到。
     * 32MB 是 www.hxmall.top 的 server 级 client_max_body_size。
     */
    const yml = read("backend/shop-app/src/main/resources/application.yml");
    expect(yml).toMatch(/max-file-size:\s*32MB/);
    expect(yml).toMatch(/max-request-size:\s*33MB/);
    // 放大上限之后不写 threshold，大包会整个进堆
    expect(yml).toMatch(/file-size-threshold:\s*\d+KB/);
  });
});

import { spawn } from "node:child_process";
import { resolve } from "node:path";
import { createRequire } from "node:module";
import automator from "miniprogram-automator";
createRequire(import.meta.url)("miniprogram-automator/out/MiniProgram").default.prototype.checkVersion = async () => {};
const PORT = 9420, CLI = "/Applications/wechatwebdevtools.app/Contents/MacOS/cli";
const PROJECT = resolve(process.cwd(), "dist/build/mp-weixin");
const sleep = ms => new Promise(r => setTimeout(r, ms));
const cli = a => new Promise(r => { const p = spawn(CLI,a,{stdio:["ignore","pipe","pipe"]}); p.on("close",r); setTimeout(r,20000); });
const auto = () => new Promise(res => { const p = spawn(CLI,["auto","--project",PROJECT,"--auto-port",String(PORT)],{stdio:["ignore","pipe","pipe"]}); let d=0; const f=()=>{if(!d){d=1;res();}}; p.stdout.on("data",()=>setTimeout(f,1500)); setTimeout(f,12000); });

await cli(["close","--project",PROJECT]); await sleep(1500);
await cli(["cache","--clean","storage","--project",PROJECT]); await sleep(1500);
await cli(["open","--project",PROJECT]); await sleep(10000);
await auto();
const mp = await automator.connect({ wsEndpoint:`ws://localhost:${PORT}` });
await sleep(6000);
await mp.screenshot({ path: resolve(process.cwd(), "tests/e2e/out/mp-after-open.png") });
const c = await mp.evaluate(() => wx.getStorageSync("shcr_community"));
console.log("绑到的聚落：", JSON.stringify(c).slice(0, 140));
try { await mp.disconnect(); } catch {}

// 싱글 모드를 서버 없이(웹소켓 차단) 브라우저로 돌리며 화면을 찍는다.
// 준비: (web) npm run dev → node scripts/screenshot-single.mjs
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';

const BASE = process.env.WEB_URL ?? 'http://localhost:5173';
const OUT = 'screenshots/single';
mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
const errors = [];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

for (const role of ['proctor', 'cat']) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(`${role} pageerror: ${e.message}`));
  page.on('console', (m) => m.type() === 'error' && errors.push(`${role} console: ${m.text()}`));
  // 오프라인 흉내: 게임 서버 웹소켓은 전부 끊음 (Vite 의 HMR 소켓은 그대로)
  await page.routeWebSocket(/\/ws$/, (ws) => ws.close());

  await page.goto(BASE);
  await page.waitForSelector('.role-btn', { state: 'visible' });
  if (role === 'proctor') await page.screenshot({ path: `${OUT}/00-lobby-offline.png` });
  await page.click(`.role-btn.${role}`);
  await page.waitForSelector('.topbar', { timeout: 5000 });
  await sleep(800);
  await page.screenshot({ path: `${OUT}/${role}-1-prepare.png` });
  await page.waitForFunction(() => document.querySelector('.tb-phase')?.textContent === '남은 시간', null, { timeout: 15000 });
  await sleep(4000);  // AI 가 움직일 시간
  await page.screenshot({ path: `${OUT}/${role}-2-playing.png` });
  if (role === 'proctor') {
    await page.keyboard.down('KeyW');
    await sleep(500);
    await page.keyboard.up('KeyW');
    await sleep(2600);
    await page.screenshot({ path: `${OUT}/${role}-3-inspect.png` });
  } else {
    const checks = await page.$$('.omr-check:not([disabled])');
    for (const c of checks.slice(0, 3)) await c.check().catch(() => {});
    await page.click('.panel.br .btn.primary').catch(() => {});
    await sleep(5000);
    await page.screenshot({ path: `${OUT}/${role}-3-known.png` });
  }
  page.once('dialog', (d) => d.accept());
  await page.click('text=기권');
  await page.waitForSelector('.result', { state: 'visible', timeout: 5000 });
  await sleep(400);
  await page.screenshot({ path: `${OUT}/${role}-4-result.png` });
  await page.click('text=역할 바꿔 한 판 더');
  await page.waitForFunction(() => document.querySelector('.tb-phase')?.textContent === '준비', null, { timeout: 5000 });
  await sleep(800);
  await page.screenshot({ path: `${OUT}/${role}-5-rematch.png` });
  await ctx.close();
}
console.log(JSON.stringify({ errors }, null, 2));
await browser.close();

// 브라우저 두 개로 실제 한 판을 돌리며 화면을 찍는다 (눈으로 확인용).
// 준비: (루트) ./gradlew bootRun, (web) npm run dev → npm run shot
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';

const BASE = process.env.WEB_URL ?? 'http://localhost:5173';
const OUT = 'screenshots';
mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
const ctxA = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const ctxB = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const a = await ctxA.newPage();
const b = await ctxB.newPage();
const errors = [];
for (const [name, p] of [['A', a], ['B', b]]) {
  p.on('pageerror', (e) => errors.push(`${name} pageerror: ${e.message}`));
  p.on('console', (m) => m.type() === 'error' && errors.push(`${name} console: ${m.text()}`));
}
const shot = (p, name) => p.screenshot({ path: `${OUT}/${name}.png` });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

await a.goto(BASE);
await b.goto(BASE);
await a.waitForSelector('.lobby-main', { state: 'visible' });
await shot(a, '01-lobby');

await a.fill('.lobby input.input:not(.code)', '호스트냥');
await a.click('text=방 만들기');
const code = (await a.textContent('.code-text', { timeout: 5000 })).trim();
await shot(a, '02-room-code');

await b.waitForSelector('.lobby-main', { state: 'visible' });
await b.fill('.lobby input.input:not(.code)', '손님감독');
await b.fill('.lobby input.code', code);
await b.click('text=입장');

await a.waitForSelector('.topbar', { timeout: 5000 });
await b.waitForSelector('.topbar', { timeout: 5000 });
await sleep(800);
const roleA = await a.textContent('.tb-role');
const [cat, proctor] = roleA.includes('냥이') ? [a, b] : [b, a];
await shot(cat, '03-cat-prepare');
await shot(proctor, '04-proctor-prepare');

// 준비 10초 → 진행
await cat.waitForFunction(() => document.querySelector('.tb-phase')?.textContent === '남은 시간', null, { timeout: 15000 });
await sleep(600);
await shot(cat, '05-cat-playing');
await shot(proctor, '06-proctor-playing');

// 냥이: 모르는 문항 체크 → 도구 사용
const checks = await cat.$$('.omr-check:not([disabled])');
for (const c of checks.slice(0, 3)) await c.check().catch(() => {});
await cat.click('.panel.br .btn.primary').catch(() => {});
await sleep(700);
await shot(cat, '07-cat-tool');

// 감독관: 앞줄 가운데 학생 쪽으로 걸어가서 2초 서 있기 → 검사
await proctor.keyboard.down('KeyW');
await sleep(600);
await proctor.keyboard.up('KeyW');
await sleep(800);
await shot(proctor, '08-proctor-near');
await sleep(2200);
await shot(proctor, '09-proctor-inspect');

// 냥이: 도구 끝난 뒤 → 정답 보임 → 다른 학생으로 빙의
await sleep(2500);
await shot(cat, '10-cat-known');
const seats = await cat.$$('.seat:not([disabled])');
if (seats.length) await seats[0].click();
await sleep(500);
await shot(cat, '11-cat-possessed');

// 살피기 왼쪽
await cat.keyboard.press('KeyQ');
await sleep(800);
await shot(cat, '12-cat-look-left');

// 감독관 파리채
await proctor.keyboard.press('Space');
await sleep(400);
await shot(proctor, '13-proctor-swat');
await shot(cat, '14-cat-after-swat');

// 기권 → 결과 화면
cat.once('dialog', (d) => d.accept());
await cat.click('text=기권');
await proctor.waitForSelector('.result', { state: 'visible', timeout: 5000 });
await sleep(500);
await shot(proctor, '15-proctor-result');
await shot(cat, '16-cat-result');

console.log(JSON.stringify({ code, catIsHost: cat === a, errors }, null, 2));
await browser.close();

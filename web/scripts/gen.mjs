// 생성 코드 만들기 (dev·build·test 전에 자동):
//  1) ../protocol 의 proto → src/gen/catchnyang (buf + protoc-gen-es)
//  2) ../rules (게임 규칙 + AI, Java) → src/gen/rules/rules.js (TeaVM) + 타입 선언
import { execSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const web = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const root = resolve(web, '..');
const win = process.platform === 'win32';
const run = (command, cwd) => execSync(command, { cwd, stdio: 'inherit' });

run('npx buf generate', web);

const gradlew = join(root, win ? 'gradlew.bat' : 'gradlew');
run(`"${gradlew}" :rules-js:generateJavaScript -q --console=plain`, root);

mkdirSync(join(web, 'src/gen/rules'), { recursive: true });
writeFileSync(join(web, 'src/gen/rules/rules.d.ts'), `// 생성됨 (scripts/gen.mjs) — rules-js/src/main/java/com/catchnyang/js/RulesJs.java 의 @JSExport 함수들
/** 새 판. humanRole = "CAT" | "PROCTOR" (상대는 AI) */
export function createMatch(configJson: string, seed: number, humanRole: 'CAT' | 'PROCTOR'): number;
/** 사람 입력 (ClientMessage 의 proto3 JSON). 다음 tick 에 적용 */
export function submit(id: number, seq: number, clientMessageJson: string): void;
export function requestSnapshot(id: number): void;
/** 한 tick (50ms) 진행. 사람에게 보낼 ServerMessage 들의 JSON 배열 문자열 */
export function tick(id: number): string;
export function isFinished(id: number): boolean;
export function dispose(id: number): void;
/** JVM 과 같은 규칙인지 확인용 기록 */
export function conformance(configJson: string, seed: number): string;
`);

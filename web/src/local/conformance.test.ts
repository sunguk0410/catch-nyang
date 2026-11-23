import { describe, expect, it } from 'vitest';
import configJson from '../../../protocol/game-config.json?raw';
import seed1 from '../../../rules/src/test/resources/conformance/seed-1.txt?raw';
import seed42 from '../../../rules/src/test/resources/conformance/seed-42.txt?raw';
import seed2026 from '../../../rules/src/test/resources/conformance/seed-2026.txt?raw';
import { conformance } from '../gen/rules/rules.js';

/**
 * 브라우저용으로 변환된 규칙(rules.js)이 서버(JVM)와 같은지.
 * 봇끼리 한 판의 모든 메시지 해시 기록을 rules ConformanceTest(JVM)가 만든 기준 파일과 비교한다.
 * 다르면: 규칙을 바꿨는데 기준을 안 갱신했거나(./gradlew :rules:test -PupdateConformance),
 * JVM·JS 에서 다르게 도는 코드가 생긴 것 (Math.hypot·cos, HashMap 순회 순서 등).
 */
describe('JVM ≡ JS (rules)', () => {
  const cases: [number, string][] = [[1, seed1], [42, seed42], [2026, seed2026]];
  for (const [seed, golden] of cases) {
    it(`seed ${seed}: 봇끼리 한 판 기록이 JVM 과 같음`, () => {
      const js = conformance(configJson, seed);
      const a = golden.replace(/\r\n/g, '\n').trimEnd().split('\n');
      const b = js.trimEnd().split('\n');
      // 처음 달라진 줄을 보여주면 어느 시점부터 어긋났는지 알 수 있음
      const firstDiff = a.findIndex((line, i) => line !== b[i]);
      expect(firstDiff === -1 ? null : { line: firstDiff + 1, jvm: a[firstDiff], js: b[firstDiff] }).toBeNull();
      expect(b.length).toBe(a.length);
    });
  }
});

import { defineConfig } from 'vitest/config';

// 실제 서버(localhost:8080)에 붙는 통합 테스트. 서버를 먼저 띄울 것: (루트) ./gradlew bootRun
export default defineConfig({
  test: {
    include: ['src/**/*.e2e.test.ts'],
    testTimeout: 60_000,
  },
});

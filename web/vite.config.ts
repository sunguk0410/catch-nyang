/// <reference types="vitest/config" />
import { defineConfig } from 'vite';

export default defineConfig({
  server: {
    port: 5173,
    // 운영과 같은 구조: 프론트와 같은 주소의 /ws 로 붙는다 (운영에선 리버스 프록시가 서버로 넘김)
    proxy: {
      '/ws': { target: 'http://localhost:8080', ws: true },
    },
    // ../protocol/game-config.json 을 직접 import 하므로 상위 폴더 접근 허용
    fs: { allow: ['..'] },
  },
  test: {
    include: ['src/**/*.test.ts'],
    exclude: ['src/**/*.e2e.test.ts'],
  },
});

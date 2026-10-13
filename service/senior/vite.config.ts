// 시니어 동네 점검단 프론트엔드 빌드 설정 (React + Tailwind v4)
import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'node:path'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    plugins: [react(), tailwindcss()],
    resolve: { alias: { '@': path.resolve(import.meta.dirname, './src') } },
    // 개발 서버에서 /api를 업무 백엔드로 넘긴다 (.env의 VITE_API_PROXY, 예: http://localhost:8080).
    // 화면과 같은 주소로 요청하게 되어 로그인·CSRF 쿠키와 CORS 설정이 따로 필요 없다.
    // 브라우저가 붙이는 Origin(개발 서버 주소)은 지운다. 남기면 백엔드가 허용 목록에 없는 CORS 요청으로 보고 403을 준다.
    server: env.VITE_API_PROXY
      ? {
          proxy: {
            '/api': {
              target: env.VITE_API_PROXY,
              changeOrigin: true,
              configure: (proxy) => proxy.on('proxyReq', (req) => req.removeHeader('origin')),
            },
          },
        }
      : undefined,
  }
})

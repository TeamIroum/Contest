import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
import './index.css'
import { REAL_BACKEND } from './api/client'

// 연결이 끊겨도 앱 화면이 열리게 앱 파일을 저장해 두는 서비스 워커 (SYN-03 일부, 실제 서비스 빌드만)
if (REAL_BACKEND && import.meta.env.PROD && 'serviceWorker' in navigator) {
  window.addEventListener('load', () => navigator.serviceWorker.register('/sw.js').catch(() => {}))
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
)
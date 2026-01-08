import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  return {
    plugins: [react()],
    define: {
      // 兼容在线代码中的 process.env.API_KEY 写法
      'process.env.API_KEY': JSON.stringify(env.API_KEY) 
    }
  }
})
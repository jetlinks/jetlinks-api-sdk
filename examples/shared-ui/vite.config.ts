import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
export default defineConfig(({ mode }) => {
 const env = loadEnv(mode, '.', '')
 return { plugins: [vue()], server: { proxy: { '/example-api': env.EXAMPLE_API_URL || 'http://127.0.0.1:8090' } } }
})

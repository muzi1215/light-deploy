import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'
import { createSvgIconsPlugin } from 'vite-plugin-svg-icons'

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => {
  const isDev = mode === 'development'

  return {
     // 打包路径 - 对应 vue.config.js 的 publicPath
    base: '/light-deploy',

    build: {
      outDir: 'light-deploy',
      assetsDir: 'static',
      sourcemap: isDev,
      rollupOptions: {
        output: {
          manualChunks: {
            'element-plus': ['element-plus'],
            'vue-vendor': ['vue', 'vue-router', 'pinia']
          }
        }
      }
    },

    // 开发服务器
    server: {
      port: 9827,
      open: true,
      proxy: {
        // 将 /api 请求代理到后端 Light Deploy 服务。
        // 后端监听 2027 端口，context-path 为 /deploy，
        // 因此 /api/server/page → http://localhost:2027/deploy/server/page
        '/deploy-api': {
          target: 'http://localhost:2027/deploy',
          changeOrigin: true,
          rewrite: (path) => path.replace(/^\/deploy-api/, '')
          //rewrite: (path) => path.replace(/^\/api/, '/deploy')
        }
      }
    },

    // 路径别名
    resolve: {
      alias: {
        '@': resolve(__dirname, 'src')
      }
    },

    // CSS 预处理器
    css: {
      preprocessorOptions: {
        scss: {
          api: 'modern-compiler',
          additionalData: `@use "@/styles/variables.scss" as *; @use "@/styles/mixin.scss" as *;`
        }
      }
    },

    plugins: [
      vue(),
      createSvgIconsPlugin({
        iconDirs: [resolve(__dirname, 'src/icons/svg')],
        symbolId: 'icon-[name]'
      })
    ]
  }
})

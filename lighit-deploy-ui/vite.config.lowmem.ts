/**
 * 临时构建配置：只在沙箱内存吃紧、sass 并发编译报 write EPIPE 时一次性使用。
 *
 * 与主配置 vite.config.ts 的唯一差异是两处降载：
 * 1. scss api 由 modern-compiler 降为 modern（不做编译器复用，减少并发 isolate）；
 * 2. rollupOptions.maxParallelFileOps 降到 2，压低并行文件操作数。
 *
 * 产物与主配置一致（outDir / assetsDir / manualChunks 保持相同），用完即删。
 */
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'
import { createSvgIconsPlugin } from 'vite-plugin-svg-icons'

export default defineConfig({
  base: '/',
  build: {
    outDir: 'dist',
    assetsDir: 'static',
    sourcemap: false,
    rollupOptions: {
      maxParallelFileOps: 2,
      output: {
        manualChunks: {
          'element-plus': ['element-plus'],
          'vue-vendor': ['vue', 'vue-router', 'pinia']
        }
      }
    }
  },
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
  css: {
    preprocessorOptions: {
      scss: {
        api: 'modern',
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
})

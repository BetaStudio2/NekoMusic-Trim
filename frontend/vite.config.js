import { fileURLToPath, URL } from 'node:url'
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'

import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

/** 本地后端默认地址（backend/src/main/resources/config.yml 的 port 默认 65535） */
const DEFAULT_DEV_PROXY_TARGET = 'http://localhost:65535'

/**
 * 把本次构建产物的哈希文件名注入 public/sw.js。
 * ------------------------------------------------------------
 * Service Worker 是 public 下的静态文件，Vite 不会处理它，因此它拿不到
 * `assets/index-<hash>.js` 这类带哈希的资源名。若不注入，离线首屏会因缺少
 * 具体 JS/CSS 而失败（必须在线访问过一次才会进入运行时缓存）。
 *
 * 这里在构建收尾（closeBundle，此时 public 已拷贝、产物已写入）：
 *   - 用扫描到的 /assets/* 替换 `/* __PRECACHE_MANIFEST__ *\/ []`；
 *   - 用资源清单的短哈希替换 `/* __CACHE_VERSION__ *\/ 'v1'`，
 *     使每次产物变化都得到新的缓存名，activate 时自动清掉上一版。
 * 找不到占位符时只告警、不阻断构建。
 */
function pwaPrecachePlugin() {
  let outDir = ''
  return {
    name: 'neko-pwa-precache',
    apply: 'build',
    configResolved(config) {
      outDir = path.resolve(config.root, config.build.outDir)
    },
    closeBundle() {
      try {
        const swPath = path.join(outDir, 'sw.js')
        if (!fs.existsSync(swPath)) return

        const assetsDir = path.join(outDir, 'assets')
        const files = []
        const walk = (dir, base = '') => {
          for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
            const rel = path.posix.join(base, entry.name)
            if (entry.isDirectory()) walk(path.join(dir, entry.name), rel)
            else files.push(`/assets/${rel}`)
          }
        }
        if (fs.existsSync(assetsDir)) walk(assetsDir)
        files.sort()

        const version = files.length
          ? crypto.createHash('sha1').update(files.join('\n')).digest('hex').slice(0, 8)
          : `t${Date.now()}`

        let sw = fs.readFileSync(swPath, 'utf8')
        const manifestMarker = '/* __PRECACHE_MANIFEST__ */ []'
        const versionMarker = "/* __CACHE_VERSION__ */ 'v1'"
        if (!sw.includes(manifestMarker)) {
          console.warn('[pwa] sw.js 未找到预缓存占位符，跳过注入')
        } else {
          sw = sw.replace(manifestMarker, JSON.stringify(files))
        }
        sw = sw.includes(versionMarker)
          ? sw.replace(versionMarker, `'${version}'`)
          : sw
        fs.writeFileSync(swPath, sw)
        console.log(`[pwa] 预缓存 ${files.length} 个资源，缓存版本 ${version}`)
      } catch (error) {
        console.warn('[pwa] 注入预缓存清单失败（不影响构建）', error)
      }
    },
  }
}

// https://vite.dev/config/
export default defineConfig(({ command, mode }) => {
  // 开发联调代理：把 /api、/version 转发到本地后端，前端与接口/音频/封面变成同源，
  // 无需后端为浏览器放开 CORS。仅 dev server 生效，生产构建不受影响。
  // 目标可用 VITE_DEV_PROXY_TARGET 覆盖（如指向线上 https://music.nekocore.cn）。
  const env = loadEnv(mode, process.cwd(), '')
  const proxyTarget = env.VITE_DEV_PROXY_TARGET || DEFAULT_DEV_PROXY_TARGET

  return {
    plugins: [
      vue(),
      command === 'serve' && vueDevTools(),
      // 构建收尾时把哈希资源清单注入 Service Worker（仅 build 生效）
      command === 'build' && pwaPrecachePlugin(),
    ].filter(Boolean),
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url))
      },
    },
    build: {
      // 构建输出
      outDir: '../backend/src/main/resources/site',
      // 输出目录在项目根之外，需显式开启清理，避免旧哈希产物堆积
      emptyOutDir: true,
      // 生产环境构建优化
      minify: 'terser',
      terserOptions: {
        compress: {
          drop_console: true, // 生产环境移除 console
          drop_debugger: true,
          passes: 2,
        },
      },
      rollupOptions: {
        output: {
          manualChunks(id) {
            if (!id.includes('node_modules')) return
            // Vue 生态必须最先归类：@vue/*（runtime-core/reactivity 等）如果漏掉，
            // 会被并进 AMLL/Pixi 大块，导致入口静态依赖 477KB 的 amll-vendor。
            if (
              id.includes('vue-router') ||
              id.includes('/@vue/') ||
              /node_modules[/\\]vue[/\\]/.test(id)
            ) {
              return 'vue-vendor'
            }
            if (id.includes('chart.js')) return 'chart-vendor'
            if (id.includes('/axios/')) return 'axios-vendor'
            if (id.includes('qrcode')) return 'qrcode-vendor'
            if (id.includes('vue-toastification')) return 'ui-vendor'
          },
          // 文件名哈希，利于缓存
          chunkFileNames: 'assets/js/[name]-[hash].js',
          entryFileNames: 'assets/js/[name]-[hash].js',
          assetFileNames: 'assets/[ext]/[name]-[hash].[ext]',
        },
      },
      // 文件大小警告阈值
      chunkSizeWarningLimit: 500,
    },
    server: {
      host: true,
      port: 5173,
      strictPort: false,
      allowedHosts: ['music.nekocore.cn', 'localhost'],
      // 开发环境也启用生产级别的优化
      hmr: true,
      // 仅在 dev server 注册；生产构建 command 为 build，不会带上代理
      proxy:
        command === 'serve'
          ? {
            '/api': { target: proxyTarget, changeOrigin: true, secure: false },
            // /api/music/file/{id} 会 302 到 /media/music/...，不代理这里开发环境会没声音
            '/media': { target: proxyTarget, changeOrigin: true, secure: false },
            '/version': { target: proxyTarget, changeOrigin: true, secure: false },
          }
          : undefined,
    },
    // 确保开发和生产环境行为一致
    define: {
      // 必须保留 Options API：vue-toastification@2.0.0-rc.5 的吐司容器内部仍用
      // Options API（data/beforeMount/mounted）实现；置为 false 会把这套运行时
      // 摇掉，容器组件渲染时报 "positions ... not defined" 且永远不挂载，表现为
      // 所有 toast 都不显示。等替换掉该库后再考虑关掉它。
      __VUE_OPTIONS_API__: true,
      __VUE_PROD_DEVTOOLS__: false,
      __VUE_PROD_HYDRATION_MISMATCH_DETAILS__: false,
    },
    // 优化依赖预构建
    optimizeDeps: {
      include: ['vue', 'vue-router', 'vue-toastification'],
    },
  }
})

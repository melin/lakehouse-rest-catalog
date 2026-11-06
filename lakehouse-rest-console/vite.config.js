import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 构建产物**直接写进服务端的静态资源目录**，而不是落到本地的 dist/。
//
// 这样做的原因：产物随 src/main/resources 一起被 maven-resources-plugin 复制进
// target/classes，再随 spring-boot-maven-plugin 进可执行 jar——前端与后端是**一次
// 构建产出**的两半，不存在「把 dist/ 拷到某处」这个中间步骤。
//
// 谁在什么时候跑这个构建：`mvn package` 里的 frontend-maven-plugin
// （见 lakehouse-rest-server/pom.xml，三条 execution 绑 generate-resources）。
// 也就是说**不再需要手工先跑 scripts/build-console.sh**——那条脚本现在只是
// 「不跑整条 Maven 也想单独重建前端 / 单独跑校验」时的便捷入口。
//
// 产物**仍然不入库**（.gitignore 排除本条 outDir）：它在构建期生成，进索引只会让
// 每次改前端都提交几十个哈希文件。这一点没变，verify-console.py 第 6 组仍在守。
//
// emptyOutDir 必须显式打开：outDir 在工程根之外，Vite 不会默认清空，
// 而 assets/ 下的文件名带内容哈希，不清空就会在目录里越积越多。
// 注意它只清 outDir 这一处（src/ 下的那份）；target/classes 里那份由 server POM
// 的 clean-derived-console-assets 单独清——是两处目录的两件事，别指望这条能替代那条。
export default defineConfig({
  plugins: [vue()],
  base: '/console/',
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    outDir: '../lakehouse-rest-spring-parent/lakehouse-rest-server/src/main/resources/static/console',
    emptyOutDir: true,
    // Element Plus 整包引入后单个 chunk 会超过默认的 500 kB 告警线。
    // 这是控制台的常见体量，调高阈值免得每次构建都刷一条噪音告警。
    chunkSizeWarningLimit: 2000,
  },
  server: {
    port: 5173,
    // 开发时把两套 API 代理到本地服务端，前端代码里始终用相对路径，
    // 生产构建后同源，不需要区分环境。
    proxy: {
      '/v1': 'http://127.0.0.1:8080',
      '/api': 'http://127.0.0.1:8080',
    },
  },
})

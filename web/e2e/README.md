# 前端浏览器回归

首次准备（在项目根目录运行）

```powershell
cd web
pnpm install --frozen-lockfile
```

如果已有依赖由 pnpm 11 安装，请用同一主版本执行安装，以免本地依赖目录的存储版本冲突。

日常复测（在 `web` 目录运行）

```powershell
pnpm test:e2e
```

命令会构建前端，并自动启动和停止 `127.0.0.1:4178` 上的独立预览服务。预览关闭后端代理，浏览器拦截应用接口并返回合成患者、号源、预约和院内资料。每项测试使用独立数据和固定时钟，未预期的接口请求会使测试失败。

测试会优先使用 Playwright 已安装的 Chromium，其次使用系统 Chrome。机器上没有 Chrome 时，可安装 Chromium 后用同一命令复测。

```powershell
pnpm exec playwright install --no-shell chromium
```

- `appointments.spec.ts` 覆盖普通挂号和智能导诊、换号确认、预约列表与详情、就诊准备以及窄屏点击区域。
- `recovery.spec.ts` 覆盖科室或医生加载、号源查询、预约确认响应丢失、就诊准备页面加载、资料库暂不可用和查询连接中断。
- `admin-knowledge.spec.ts` 覆盖独立登录、患者会话拒绝、引用与索引状态、下架与失败恢复、Markdown 预览，以及草稿索引重试与确认发布。
- `admin-tool-logs.spec.ts` 覆盖窄屏任务定位、失败原因、重试、耗时和加载后续记录。
- `admin-tool-registry.spec.ts` 覆盖窄屏菜单、工具用途、参数、必填项和患者预约确认提示。
- `admin-model-config.spec.ts` 覆盖模型配置读取失败后重试、生效超时、各层重试策略、演示模式和窄屏布局。

只跑某个测试文件可用 `pnpm test:e2e recovery.spec.ts`。失败时，截图、上下文和浏览器 trace 保存在项目根目录的 `target/frontend-e2e/`。使用 `pnpm exec playwright show-trace <trace.zip 路径>` 查看失败步骤。

完整系统与真实模型的验收见 [S6 验收和本地演示](../../docs/product/s6-local-acceptance.md)。

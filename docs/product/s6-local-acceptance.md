# S6 综合验收与本地演示

2026-11-18 用 Chrome 157、隔离 MySQL 8.0.34、真实 DeepSeek 和真实 Qdrant 1.13.6 完成首轮。使用仓库内的合成患者、号源和医院资料。MySQL 应用 V1–V8，导入 6 篇资料。Qdrant 集合包含 29 个向量，索引失败和待处理均为零。Agent 追踪记录了 6 轮真实 `DeepSeekDecisionModel` 决策。预约后的就诊准备使用关联预约事实和只读资料检索。

## 首轮预约主线验收

| 场景 | 已核对的结果 |
| --- | --- |
| 从明确科室直接查号 | 输入“我想挂心内科，帮我查最近有号的时间，医生不限”，真实模型调用查号工具，页面列出 3 条候选。选择后形成确认单，预约记录仍为零。 |
| 确认前问资料 | 提问就诊是否需要身份证和预约凭证，真实模型调用医院资料工具。从页面展开 `VISIT-001` v1 的“证件与凭证”，核对正文和版本。原条件与确认单保留。 |
| 多轮调整与重新选号 | 对话把日期改成明天、时段设为必须下午。页面改为上午并保留日期，下一轮对话指定候选里的林医生。实际候选满足日期、时段及医生要求，确认单从 v1 更新至 v3。旧确认被拒绝，刷新恢复新确认单。“好的，就按这个方案”保留原方案，预约数仍为零。 |
| 人工确认与预约详情 | 点击“确认预约”后生成并核验一笔，与选中的号源、日期、时段、科室和医生一致。“查看原预约”直接定位这笔记录。 |
| 预约后带来源的咨询 | 从原预约进入“就诊准备”，显示原医生、科室和时间；询问证件可展开同版本院内引用。宠物能否入诊室返回“资料不足”。咨询入口不执行新预约，刷新复用同一任务。另一患者无法读取或开启该咨询。 |
| Qdrant 停机与恢复 | 停止专用 Qdrant 进程后，同一咨询返回“资料库暂时无法查询”，无新增引用，追踪记到两次只读尝试。重启后同一任务恢复引用，原预约不变。 |
| 抢号后的继续办理 | 在第二个任务中由另一患者先占同一个号源。Agent 确认返回号源不可用，条件不变。重新查号排除已占项，待患者再次选择和确认。 |
| 提交后的回包丢失 | 拦截并丢弃数据库已提交的响应。页面点“核验原预约”取回原编号，同键重试和刷新仍得到同一笔。数据库核对患者甲两笔 Agent 预约、患者乙一笔普通挂号，每个号源只写入一次。 |

首轮 8 个 Chrome 场景通过。另用重建脚本恢复全部 29 个向量，回同一笔预约的就诊准备重新发问，仍可展开 `VISIT-001` v1。首轮隔离 MySQL 套件 148 项、真实 Qdrant 套件 16 项通过。用锁文件在新目录安装依赖并构建通过，后端打包通过。消息流文本与保存回复一致，任务追踪与预约结果可对照。

测试证据在本机 `target/s6-verification/`。主线及失败恢复见 `browser.json`，重建后首问见 `after-rebuild.json`。截图包括 `02-adjusted-confirmation.png`、`03-visit-preparation.png`、`04-index-outage.png` 和 `05-pending-confirmation.png`。自动化日志见 `mysql-suite.log`、`qdrant-suite.log` 和 `clean-web-build.log`。

## 医生、科室和资料生命周期补验

医生和科室介绍接入原有只读资料工具。DeepSeek 发起查询，后端按患者问题匹配当前启用的唯一目录条目，再核对资料类型、实际医生和科室归属、版本及发布状态。答复只使用该对象的相应章节。维护操作新增本地命令，复用现有 MySQL 表和 Qdrant 集合。

| 场景 | 已核对的结果 |
| --- | --- |
| 医生和科室来源 | Chrome 查询林医生、刘医生和心内科门诊，看到了对应的 `DOCTOR-3001` v1、`DOCTOR-3006` v1 和 `DEPT-2001` v1。展开来源能核对版本、标题、章节、正文和出处。 |
| 医生归属、启用与比较 | 故意导入科室归属有误的合成医生资料，实际回答只引用归属正确的资料。用隔离目录临时停用医生后仍保留原向量，查询返回资料不足。比较两位医生的提问没有生成能力排名。 |
| 更新后只引用 V2 | 更新并发布 `DOCTOR-3001` v2。通过 Qdrant 的滚动查询确认 V1、V2 向量同时存在。新任务只引用 V2，用浏览器展开新版原文。 |
| 资料中的伪指令 | V2 来源原文带有合成验收指令。正常回答和同任务后续预约规则咨询均按实际资料返回，浏览器未显示成功预约，数据库没有新增预约。 |
| 下架且保留向量 | 使用 `manage-knowledge.ps1 -Action Archive` 下架。两版向量仍保留在 Qdrant 时，新会话返回 `NO_EVIDENCE`，没有来源卡片。既有对话保留作答时的引用快照。 |
| 重复导入与重建 | 将 V1、V2 各重新导入，均跳过。状态命令显示两版仍为 `ARCHIVED`。重建后两版向量均排除，其他仍发布的医生资料继续可查。 |
| 索引失败可见、可重试 | 将隔离测试集合创建成错误维度，29 个片段索引失败。状态命令显示失败片段及原因。以正确维度重建后索引写入 29 个片段，失败和待处理均为零。 |

全程用合成资料与独立数据库验收。首版资料范围为有来源的场景，未覆盖的科室、医生或具体问题仍会按资料不足处理。

### 收尾结果

补验通过 7 个资料场景和 2 个预约需求场景。最后一次修正后，预约主线的 8 个 Chrome 场景也全部重跑通过。隔离 MySQL 相关回归 153 项通过，其中 62 项为 MySQL 集成测试。真实 Qdrant 适配器 16 项通过。最终合计 169 项自动化测试通过，无失败、错误或跳过。Vue 类型检查、前端构建及后端打包通过。

Windows 上的 Qdrant 在一次适配器测试删除集合时返回了系统错误 5（拒绝访问）。在同一个隔离服务上独立复跑，16 项全部通过。真实应用的下架后重建、停机和重启场景也单独通过。

收尾证据在 `target/s6-finish-final/`。医生及版本资料见 `browser-lifecycle.json`，分诊和严格/优先对照见 `browser-triage.json`，预约主线复跑见 `browser.json`。自动化汇总见 `regression.json`。失败原因与修复后重建见 `target/s6-finish/status-failed.log` 和 `target/s6-finish/initial-rebuild.log`。

## S6 退出条件核对

| 验收编号 | 证据 |
| --- | --- |
| A01 | Chrome 中只问就诊方向时，DeepSeek 给出方向并询问是否按该科查号。患者同意后才查号。明确科室可以直查，虚构科室显示目录未找到。验收中发现“最近咳嗽”触发了近期号源逻辑，已拆开这两个判断并定向复测。 |
| A02 | 同一任务先通过对话改日期与时段，再用页面和对话补条件。已保存条件保留。 |
| A03 | 页面显示真实号源，最多三条。候选包含差异说明，选择与确认单一致，查询及选择阶段预约数不增加。 |
| A04 | 同日无晚间号时，“必须晚上”返回无号。明确说可放宽后返回同日其他时段，候选标明“时段不同”。 |
| A05 | 询问资料保留当前确认单，实际调整条件使旧单失效，需要重新选择。 |
| A06 | 本轮隔离 MySQL 回归和 [S1 浏览器证据](s1-patient-session-acceptance.md)覆盖普通挂号严格查询、筛选变更及写入时有效性。 |
| A07 | Chrome 验证聊天同意不写入、旧确认失效及显式确认。过期和非本人确认拒绝保留对应自动化用例。 |
| A08 | Chrome 双患者抢同一号，失败方保留原条件。确认响应丢失后同键返回原单。消息重试与同键换号源用例见 [S5 记录](s5-booking-recovery.md)及本轮回归。 |
| A09 | 本轮真停 Qdrant 验证资料暂不可查、有限重试及重启恢复。模型和工具超时继续执行 S5 的自动化用例。 |
| A10 | Chrome 刷新恢复条件、新确认单、既有咨询和已核验预约。网络中断后可以从原任务继续核验。 |
| A11 | 已提交但丢弃响应时，从原请求查回同一预约编号。核验与重试没有产生额外预约。 |
| A12 | 真实医生、科室、就诊准备来源可以展开。两位医生各自引用自己的资料，停用、错误归属与比较排名反例通过。 |
| A13 | Chrome 分别验证无依据、真实 Qdrant 故障和伪指令材料。自动化覆盖高分无关资料的过滤。 |
| A14 | 从预约详情进入只读咨询，查看已知就诊事实并返回原单。两种预约来源的浏览器证据见 [S4 记录](s4-visit-preparation.md)。 |
| A15 | 本轮实测跨账号访问咨询和追踪被拒绝，S1 登录/退出及接口隔离与本轮自动化回归一并作为证据。 |
| A16 | 已通过维护命令验证同内容去重、更新升版本、下架、新问答过滤历史向量、状态及失败查看、恢复重建。 |
| A17 | 按任务查看 DeepSeek 决策、工具结果和耗时、预约写入与核验。流式回复与最终保存内容一致。 |
| A18 | 空 MySQL、空向量集合和新目录依赖安装按下述流程验证。保留启动步骤、证据、演示流程及可核对项目描述。 |

## Windows 本地启动

实测环境为 JDK 17.0.19、Maven 3.9.4、Node 24.13、pnpm 10.34.8、MySQL 8.0.34 和 Qdrant 1.13.6。前端依赖使用仓库的 `web/pnpm-lock.yaml`。各命令从项目根目录执行。以下示例使用专用 MySQL 端口 `13317`、Qdrant 端口 `16333`、后端 `8080`、前端 `5173`。

**终端一启动独立 MySQL。** 已有专用演示 MySQL 可直接使用，并将后续端口及凭据替换为该实例的配置。

```powershell
$mysqlBin = Split-Path (Get-Command mysql.exe).Source
$mysqlBase = Split-Path $mysqlBin
$demoData = Join-Path (Get-Location).Path 'target/local-demo/mysql'
if (-not (Test-Path -LiteralPath $demoData)) {
    New-Item -ItemType Directory -Path $demoData | Out-Null
    & "$mysqlBin/mysqld.exe" --no-defaults "--basedir=$mysqlBase" "--datadir=$demoData" --initialize-insecure --console
    if ($LASTEXITCODE) { throw '演示 MySQL 初始化失败' }
}
& "$mysqlBin/mysqld.exe" --no-defaults "--basedir=$mysqlBase" "--datadir=$demoData" --port=13317 --bind-address=127.0.0.1 --mysqlx=OFF --default-time-zone=+00:00 --console
```

**终端二启动 Qdrant。** 实测使用 Windows 原生程序。将 `qdrant.exe` 加入 PATH，或把下列第一行替换成你解压后的程序路径。

```powershell
$qdrantExe = (Get-Command qdrant.exe).Source
New-Item -ItemType Directory -Path target/local-demo/qdrant -Force | Out-Null
Push-Location target/local-demo/qdrant
try {
    $env:QDRANT__SERVICE__HOST = '127.0.0.1'
    $env:QDRANT__SERVICE__HTTP_PORT = '16333'
    $env:QDRANT__SERVICE__GRPC_PORT = '16334'
    & $qdrantExe
} finally { Pop-Location }
```

使用项目 [Docker Compose](../../docker-compose.yml) 也能启动 Qdrant。默认端口为 `6333`，后续命令中的 Qdrant URL 对应修改。Compose 路径没有在本轮重跑。

**终端三初始化数据、资料索引并启动后端。** 命令里的空数据库密码只用于前述回环地址上的一次性实例。真实 DeepSeek 读取当前终端的 `DEEPSEEK_API_KEY`。现有配置无需变更。重建索引时将 `-Rebuild` 加到索引命令，确认它指向当前演示库和专用集合。

```powershell
if ([string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY)) {
    throw '请先在当前 Windows 环境配置 DEEPSEEK_API_KEY'
}
$env:MEDIFLOW_DB_URL = 'jdbc:mysql://127.0.0.1:13317/mediflow_demo?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
$env:MEDIFLOW_DB_USERNAME = 'root'
$env:MEDIFLOW_QDRANT_URL = 'http://127.0.0.1:16333'
$env:MEDIFLOW_QDRANT_COLLECTION = 'mediflow_local_demo'
$env:MEDIFLOW_KNOWLEDGE_INDEX = 'qdrant'
$env:MEDIFLOW_EMBEDDING_PROVIDER = 'local'

mysql.exe --no-defaults --host=127.0.0.1 --port=13317 --user=root --execute 'CREATE DATABASE IF NOT EXISTS mediflow_demo CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;'
mvn -q -DskipTests package
./scripts/import-knowledge.ps1 -HostName 127.0.0.1 -Port 13317 -Database mediflow_demo -User root -Password ''
./scripts/seed-demo-slots.ps1 -Port 13317 -Database mediflow_demo -User root -Password '' -Days 14
./scripts/index-knowledge.ps1 -HostName 127.0.0.1 -Port 13317 -Database mediflow_demo -User root -Password '' -QdrantUrl $env:MEDIFLOW_QDRANT_URL
java -jar target/mediflow-agent-0.1.0-SNAPSHOT.jar '--spring.profiles.active=mysql' '--spring.datasource.password='
```

首次导入会由 Flyway 初始化专用库。导入检查 `failed=0`，索引检查 `failed=0 stillPending=0`。隔天演示可以重新执行号源脚本，已有预约仍会保留。需要从零复演时为数据库和 Qdrant 集合分别取一个新的名字。

**终端四启动页面。** 打开浏览器中的 `http://127.0.0.1:5173/guide`，使用[本地演示账号](s1-patient-session-acceptance.md)登录。Vite 代理已指向后端 `8080`。

```powershell
Set-Location web
pnpm install --frozen-lockfile
pnpm exec vite --host 127.0.0.1 --strictPort
```

## 本地资料维护

导入会发布新内容，同编号的历史版本保留用于追溯。已经导入过的内容再次出现时跳过，包括已下架版本和早于当前版本的同一份内容。浏览器问答通过 MySQL 的当前发布状态核对 Qdrant 命中。

使用[资料维护脚本](../../scripts/manage-knowledge.ps1)查看版本及片段索引状态。演示库的连接参数和启动步骤相同。

```powershell
./scripts/manage-knowledge.ps1 -Action Status -Database mediflow_demo -DocNo DOCTOR-3001 -HostName 127.0.0.1 -Port 13317 -User root -Password ''
```

确实需要撤下指定演示资料时，传入 `Archive` 和明确编号。命令将该编号的全部版本设为已下架，并输出此次实际变更的版本数。新问答随即排除该资料，历史对话保留作答时的引用。两条维护命令使用项目已有的表，可在应用运行时对同一演示库执行。

```powershell
./scripts/manage-knowledge.ps1 -Action Archive -Database mediflow_demo -DocNo DOCTOR-3001 -HostName 127.0.0.1 -Port 13317 -User root -Password ''
```

索引失败时，状态输出显示失败片段编号和原因。修正依赖或配置后重新执行索引命令。需要重建时沿用上述专用数据库和集合配置并传入 `-Rebuild`，重建只生成仍已发布的资料向量。

## 求职演示流程

现场演示前确认页面能看到近期号源，并用合成患者测试一次带来源的问答。主线约 3–4 分钟。

1. **展示 Agent 如何推进任务。** 输入“我想挂心内科，帮我查最近有号的时间，医生不限”。展示真实候选并选一条。此时页面出现确认单，可以顺手问“门诊就诊需要带什么证件？”，展开资料来源，确认单会保留。
2. **展示对话与页面共用条件。** 输入“请改成明天，必须下午”，选一条新候选。再在表单把时段改成上午并保存，指出原日期仍保留、旧确认单已失效。可以从上午候选取一个医生名，再通过对话指定该医生并重新选择。
3. **展示写入和核验。** 说“好的，就按这个方案”，观察任务继续等患者点击。点击“确认预约”，打开“查看原预约”，核对医生、日期、时间和编号。
4. **展示服务接续。** 从这笔预约打开“就诊准备”，问“这次去要带哪些证件”，展开“证件与凭证”的原文及版本，然后返回原预约。
5. **展示工程细节。** 用抢号或提交回包丢失的截图说明恢复流程。讲清楚抢号时保留原需求并重新确认，提交不确定时用原请求核验。准备一份无患者原话和密钥的任务追踪摘要，展示一次查询/写入/核验的前后关系。

简历可描述为：实现医院预约 Agent，接入 DeepSeek 的只读工具决策和 MySQL/Qdrant 资料检索，支持多轮修改预约条件、候选比较和用户确认。通过请求幂等与原单核验处理抢号和提交结果不确定，在本地浏览器验证真实模型、带来源问答及失败恢复。

# StressLab · UltraBARX 整机性能/热测试台

运行于 LineOS / UltraBar 条形屏主机（Android）的压测与热测试工具，可对设备施加 CPU / 内存 / IO / GPU 负载，实时监测四热区温度，并作为 **LineOS 插件**被主机宿主统一调度，同时提供 PC 端 Web 控制台。

- 应用包名：`com.thermal.stress`
- 注册名：`StressLab`（宿主 / 前台通知 / 控制台统一）
- 最低系统：Android 7.0（minSdk 24）；目标 targetSdk 29，compileSdk 34

---

## 一、主要功能

### 负载压测
- **CPU 烧核**：25 / 50 / 75 / 100% 四档负载，占空比调节，固定 4 线程
- **混合烧机**：CPU 满载叠加 1024MB 内存占用 / 多线程 IO 写入（cpu_mem、cpu_io、cpu_mem_io 三种组合）
- **GPU 负载**：依赖 Activity 的 GL 渲染视图（无头插件 action 不开启）
- 一键停止全部负载

### 温度监测与保护
- 四个热区实时温度 + 整机最高温、负载占用率
- **三态超温保护**（单一数据源，终端与 PC 一致）：
  | 模式 | 行为 |
  |---|---|
  | 0 关闭 | 不提醒、不停止；切换即清除当前告警 |
  | 1 仅提醒 | 终端红框 + 警报音、PC 告警条，负载继续运行 |
  | 2 提醒并停止（默认） | 超阈值自动停止全部负载并告警 |
- 2℃ 回差重新武装，避免阈值附近反复触发；默认阈值 85℃

### 数据记录
- 负载期间每秒一条 CSV（时间戳、各热区温度、负载状态等）
- PC 控制台支持记录**多选 / 全选删除、勾选打包 zip 下载、单文件下载**
- 正在写入的当前记录禁止删除（服务端 + 前端双重保护）

### LineOS 插件协议（v2）
- 作为 TCP 客户端主动连接本机宿主 `127.0.0.1:39001`，SDK `ultrabarIntegrated:1.0.12`（Netty），断线自动重连
- 宿主扫描 manifest `meta-data ultrabar.plugin` 后自动拉起前台服务，无需打开 Activity
- 标准三段式 `describe → options → call`
- 四个 action：
  | actionId | 参数 | 说明 |
  |---|---|---|
  | `thermal.cpu_burn` | `level`（25/50/75/100，REMOTE 实时候选） | CPU 烧核 |
  | `thermal.mixed_burn` | `profile`（cpu_mem / cpu_io / cpu_mem_io） | 混合烧机 |
  | `thermal.stop` | 无 | 停止全部负载 |
  | `thermal.status` | 无 | 四热区温度 / 负载 / 阈值 |

### PC Web 控制台
- 终端右下角实时显示动态控制台地址（亮蓝加粗）
- 实时状态卡片、负载下发、保护模式切换、CSV 记录管理
- 浏览器标签页 favicon；中英文双语

---

## 二、端口设计（全部动态，无硬编码）

| 端口 | 用途 | 分配 |
|---|---|---|
| 39001 | LineOS 宿主协议端口（TCP，插件作为客户端连接） | 宿主固定，SDK 默认 |
| `httpPort` | APP 自身 HTTP 服务（API + 控制台 + `/api/plugin`） | `NanoHTTPD(0)` 系统分配，`getListeningPort()` 获取，仅绑定回环 |
| `debugPort` | 插件调试 HTTP 服务（宿主注册结果下发） | `RegisterResultPayload.configServer.port` |

- 进程级单例 `Backend` 保证宿主只拉起 Service、不打开 Activity 时业务后端也能就绪
- `debugPort` 端口对外暴露完整 PC 控制台：`/` 出控制台页面、`/api/*` 反向代理到回环 `httpPort`、`/debug` 同控制台
- 插件协议调试接口（`/api/status`、`/api/invoke`）强制 `sessionToken` 校验，无效返回 403
- 多 APP 并存不会因固定端口冲突

---

## 三、工程结构

```
app/src/main/
├── AndroidManifest.xml          # 权限、PluginService 声明（REGISER 原文拼写）、singleTask
├── assets/web/
│   ├── index.html               # PC Web 控制台（单文件，内联 CSS/JS，中英双语）
│   ├── favicon.png / favicon64.png
└── java/com/thermal/stress/
    ├── Backend.kt               # 进程级后端单例（Engine + 动态端口 WebServer）
    ├── Engine.kt                # 业务状态层：负载配置、监测循环、三态保护、快照
    ├── MainActivity.kt          # 终端长条屏 UI（四热区/告警/右下角控制台地址）
    ├── StressService.kt         # 负载前台服务（复用 Backend）
    ├── CsvLogger.kt             # 每秒 CSV 记录
    ├── SystemStats.kt / ThermalReader.kt / root/RootControl.kt
    ├── load/                    # CpuBurner / MemoryBurner / IoBurner / LoadManager
    ├── gl/GpuView.kt            # GPU GL 负载视图
    ├── web/WebServer.kt         # 回环 HTTP：状态/配置/保护/CSV 增删打包
    └── plugin/
        ├── PluginService.kt     # 被宿主拉起的前台服务（specialUse + 降级）
        ├── ThermalPluginBridge.kt   # 注册 / actions / describe/options/call
        ├── PluginState.kt       # sessionId/token/debugPort volatile 状态
        └── PluginDebugServer.kt # debugPort 控制台 + 反代 + token 鉴权
```

---

## 四、构建与安装

```powershell
# 需 JBR（Android Studio 自带）
$env:JAVA_HOME="D:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug --console=plain

# 装机
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

依赖仓库需含 jitpack（`settings.gradle` 已配置），核心依赖：
`com.github.yaobin-kid:ultrabarIntegrated:1.0.12`、`netty-all:4.1.94.Final`、
`jackson-databind:2.15.2`、`slf4j-simple:2.0.7`、`org.nanohttpd:nanohttpd:2.3.1`。

### 图标重新生成
```powershell
.\generate_icons.ps1   # 输入 TherTest.png，输出全密度满幅直角 PNG（4× 超采样）
```
> 图标**不使用** `mipmap-anydpi-v26` adaptive XML：宿主对 adaptive 合成出四角透明的圆角形状图，
> PC 端会按圆形渲染；满幅直角 PNG 才能在终端与 PC 调试软件中一致显示方形。

### 验证
```bash
adb logcat -s ThermalPlugin:* ThermalPluginSvc:*      # 注册成功 / sessionId / debugPort
curl http://<设备IP>:<debugPort>/api/plugin            # registered / debugUrl
```

---

## 五、版本迭代记录

> 版本号自动递增：每次真正打包 `versionCode` +1，`versionName = 1.0.<code>`，
> 状态存于 `app/version.properties`（`gradle tasks` 等非打包操作不计数）。

### 1.0.2（versionCode 2）
- **新增** 三态超温保护（关闭 / 仅提醒 / 提醒并自动停止），修复"关闭保护后终端与 PC 仍告警"的问题——终端原先只比对温度未读开关，PC 告警条状态残留
- **新增** PC 控制台 CSV 测试记录管理：复选多选、全选、批量删除、勾选打包 zip 下载、单文件下载；当前写入记录受保护不可删
- **新增** Web 控制台 favicon（32/64px）
- **优化** 控制台标题改为 `UltraBARX StressLab (Performance Test Console)`；记录卡片三个按钮成组排列
- **优化** 终端右下角动态控制台地址（亮蓝 18sp 加粗，独立于状态行）
- **修复** APP 图标在 PC 调试软件（UltraWorkSpace，经 com.ultrabar.hub:7431 取图标）中显示为圆形：移除 adaptive XML 通道，统一满幅直角 PNG，并全密度 4× 超采样解决模糊
- **修复** 图标变更后宿主 / hub 长寿命进程持有旧资源导致的缓存异常（需重启对应进程刷新）

### 1.0.1（versionCode 1）
- **新增** LineOS 插件协议 v2 完整接入：PluginService 前台服务（specialUse + 普通 startForeground 降级）、ThermalPluginBridge 注册与四个 action、PluginState、token 鉴权调试服务
- **新增** 进程级 Backend 单例；**HTTP 服务端口完全动态化**（去除固定 8091，多 APP 并存不冲突）
- **新增** 宿主分配的 debugPort 直接承载完整 PC 控制台，`/api/*` 反向代理回环服务
- **改名** 应用名统一为 StressLab（启动器名、插件注册名、通知、FGS 声明）
- **新增** `/api/plugin` 发现端点，返回注册状态与控制台地址

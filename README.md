# OrigCore

OrigCore 是以 [PaperMC/Paper](https://github.com/PaperMC/Paper) 為基底、針對 **Minecraft 1.21.11** 維護的伺服器核心。

OrigCore 0.1.0 的定位很明確：**以 Paper 為基底，先把「可受控的背景工作排程」與「執行緒安全邊界」做正確、做誠實**，
而不是先做一個聽起來很厲害但沒有實測數據的平行化核心。

> **請先閱讀〈能力界線〉。** OrigCore 0.1.0 **沒有**把 Minecraft 世界 Tick 平行化，
> **沒有**實作區域化 Tick（regionised ticking），也**不會**因為 CPU 核心數多就自動加速。
> 任何聲稱相反的說法都不是本專案的主張。

---

## 目錄

- [目前狀態](#目前狀態)
- [能力界線（重要）](#能力界線重要)
- [系統需求](#系統需求)
- [快速開始](#快速開始)
- [建置](#建置)
- [執行](#執行)
- [設定檔：`config/origcore.yml`](#設定檔configorigcoreyml)
- [診斷輸出](#診斷輸出)
- [專案結構](#專案結構)
- [分支策略](#分支策略)
- [版本與上游對應](#版本與上游對應)
- [語言慣例](#語言慣例)
- [文件](#文件)
- [授權](#授權)

---

## 目前狀態

| 項目 | 內容 |
| --- | --- |
| OrigCore 版本 | `0.1.0` |
| 支援的 Minecraft 版本 | **僅 1.21.11** |
| 上游 | PaperMC/Paper，分支 `ver/1.21.11`，commit `c5eb0790f199da6c38d0a650e1e5cd5415b28185` |
| 建置工具鏈 | Gradle Wrapper 9.2.0，Java toolchain 21（`options.release = 21`） |
| 產出 | `origcore-bundler-<版本>-mojmap.jar`、`origcore-paperclip-<版本>-mojmap.jar` |
| 建置狀態 | `applyPatches`、`:paper-server:compileJava`、`:paper-server:origcoreTest`、`createMojmapBundlerJar`、`createMojmapPaperclipJar` 皆已驗證成功 |
| 啟動驗證 | 已用產出的 JAR 實際啟動、產生世界、正常 `stop`（結束碼 0） |

已在 0.1.0 完成的功能：

- 以 Paperweight 的 patch 機制在上游之上套用 OrigCore 修改（不複製上游原始碼、不重排上游檔案）。
- OrigCore 品牌識別（啟動橫幅、`Bukkit#getName()`、JAR manifest），同時保留插件相容性。
- 受控背景工作排程器：固定大小執行緒池 + 有界佇列 + 直接拒絕（**永不** 由呼叫端代跑）。
- 非阻塞的 `MainThreadDispatcher`：使用 Paper 既有的主執行緒工作佇列，**不做**同步等待。
- `ThreadSafetyGuard`：開發／測試用的執行緒安全違規偵測（違規類型、執行緒名稱、呼叫堆疊、累計次數）。
- CPU 資源偵測：實體核心數、作業系統邏輯處理器數、JVM 可用處理器數、容器配額**分開存放**，取不到就回報「未知」。
- 核心數設定：`threading.worker-threads` 可填固定值，`0` 代表自動識別；偵測不到時可用 `cpu.*` 明確宣告（標示為 `operator-declared`，不冒稱偵測結果）。
- 設定檔驗證：範圍檢查、明確錯誤、不沉默接受不合法值；`region-tick.enabled` 強制為 `false`。
- 診斷指標：TPS、MSPT、主執行緒耗時、工作執行緒數與忙碌數、佇列長度、工作計數、佇列等待與執行耗時百分位數。
- OrigCore 專屬單元測試（47 項，不需要啟動伺服器）。

---

## 能力界線（重要）

OrigCore 0.1.0 **刻意**沒做以下事情。這些不是「還沒做完」，而是現階段明確不主張的能力：

| 專案 | 0.1.0 狀態 | 說明 |
| --- | --- | --- |
| 區域化 Tick / 平行世界 Tick | **未實作** | `threading.region-tick.enabled` 是保留設定，強制 `false`。設為 `true` 只會得到警告，不會有任何效果。 |
| 把原版（vanilla）工作卸載到背景 | **未實作** | 0.1.0 的工作池是給「未來可安全分離的純計算工作」用的骨架；**目前沒有任何原版 Tick 工作被移到背景執行**。因此與未修改的 Paper 相比，0.1.0 應視為效能中立。 |
| 因核心數多而自動加速 | **不主張** | 20 核心的機器不會自動變成 20 倍。工作執行緒數只是 OrigCore 自己管理的背景執行緒數量。 |
| 多版本支援 | **不支援** | 只維護 1.21.11。跨版本的用戶端連線請自行以 ViaVersion／ViaBackwards 之類的外部方案處理；OrigCore 不對其結果負責。 |
| Folia 式架構 | **未移植** | Folia 僅作為架構研究參考。OrigCore 沒有整套移植 Folia。 |
| 熱重載設定 | **不支援** | 修改 `config/origcore.yml` 後需重新啟動。 |

### 具體的「不可以」

- 背景工作**不得**修改方塊、世界狀態、實體或玩家背包。
- 背景工作**不得**呼叫依賴主執行緒的 Bukkit／Paper API。
- 背景工作**不得**觸發必須在主執行緒上觸發的插件事件。
- **不可以**把 `ConcurrentHashMap` 之類的容器當成「世界狀態已具備執行緒安全」。
- **不可以**在主執行緒上阻塞等待背景工作（`submitAndWait` 在主執行緒上會直接丟出例外）。
- **不可以**只憑 TPS 判定效能好壞；也**不可以**把工作池忙碌率當成整台機器的 CPU 使用率。

---

## 系統需求

### 建置

- JDK 21（Paper 的 `options.release = 21`）。
  - Gradle toolchain 會自行尋找或用 provision 取得符合的 JDK。
  - 已驗證環境：Temurin JDK 25.0.4 作為 Gradle 執行 JVM，編譯目標仍為 Java 21。
- Windows、Linux、macOS 皆可。Windows 上請先執行：

  ```powershell
  git config --global core.longpaths true
  ```

  （反編譯產生的路徑會超過 Windows 預設的 260 字元上限。）

- 記憶體：建議至少 6 GB 給 Gradle（範例：`GRADLE_OPTS="-Xmx6g -XX:MaxMetaspaceSize=1g"`）。
- 需要網路：首次建置會下載 Mache mappings、Spigot build data、Paperclip 與相關依賴。

### 執行

- Java 21 或以上。本次驗證以 Temurin 25.0.4 執行產出的 JAR。

---

## 快速開始

```powershell
# 1) 套用 Paper patch 堆疊（第一次必做）
.\gradlew.bat applyPatches

# 2) 執行 OrigCore 單元測試（不需要啟動伺服器）
.\gradlew.bat :paper-server:origcoreTest

# 3) 產出可用的伺服器 JAR（使用 Mojang mappings 變體）
.\gradlew.bat createMojmapBundlerJar createMojmapPaperclipJar
```

產出位於 `paper-server/build/libs/`：

```
origcore-bundler-1.21.11-R0.1-SNAPSHOT-mojmap.jar
origcore-paperclip-1.21.11-R0.1-SNAPSHOT-mojmap.jar
paper-server-1.21.11-R0.1-SNAPSHOT.jar
```

**要拿去開伺服器請用 `origcore-bundler-*-mojmap.jar`**（或 paperclip 變體）。
`paper-server-*.jar` 不是可獨立啟動的伺服器。

> **注意：** 請使用 `-mojmap` 變體。上游已刻意停用 `reobf` 變體的產生，`createReobf*` 會失敗，這是預期行為。

---

## 建置

完整說明（含 patch 工作流程、疑難排解、CI）請見 [`docs/BUILD.md`](docs/BUILD.md)。這裡只列常用指令：

| 目的 | 指令 |
| --- | --- |
| 套用上游 patch 堆疊 | `gradlew applyPatches` |
| 重新產生上游 patch 檔案 | `gradlew rebuildPatches` |
| 編譯 | `gradlew :paper-server:compileJava` |
| OrigCore 單元測試 | `gradlew :paper-server:origcoreTest` |
| 打包（bundler） | `gradlew createMojmapBundlerJar` |
| 打包（paperclip） | `gradlew createMojmapPaperclipJar` |
| 完整檢查 | `gradlew check`（含 Paper 上游自己的測試；OrigCore 尚未在 Windows 上驗證此任務，見 `docs/BUILD.md`） |
| 開發用伺服器 | `gradlew runServer`（或 `runBundler`／`runPaperclip`） |

---

## 執行

```powershell
java -Xms2G -Xmx4G -jar origcore-bundler-1.21.11-R0.1-SNAPSHOT-mojmap.jar --nogui
```

第一次啟動會在 `config/origcore.yml` 產生帶繁體中文註解的設定檔，並在 `logs/` 寫入記錄。

### 自動化啟動驗證

本專案附帶 `tools/smoke-test.ps1`，用於可重現的啟動／關閉驗證（不是效能測試）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\smoke-test.ps1 `
  -Jar paper-server\build\libs\origcore-bundler-1.21.11-R0.1-SNAPSHOT-mojmap.jar `
  -RunDir run-origcore -StartupWaitSeconds 45 -MaxHeapMB 2048
```

它會啟動伺服器、擷取主控台輸出、送出 `stop`、等待行程結束，並回報結束碼。

---

## 設定檔：`config/origcore.yml`

設定檔由 OrigCore 於首次啟動時自動產生（UTF-8 **無 BOM**，含繁體中文註解）。

### `threading`

「核心數」就是 `threading.worker-threads`：設 `0` 讓 OrigCore **自動識別**，或直接填 `1` ~ `64` 固定使用這個數量。

| 鍵 | 預設 | 允許值 / 範圍 | 說明 |
| --- | --- | --- | --- |
| `threading.enabled` | `true` | `true` / `false` | `false` 完全不建立背景工作池（其餘品牌、設定驗證與診斷仍保留，行為等同未修改的 Paper）。 |
| `threading.worker-threads` | `0` | `0` ~ `64` | **★核心數設定★**。`0` = **自動識別**（依 `cpu.*` 宣告值或偵測到的可用處理器數推導）；`1` ~ `64` = 固定使用這個數量，不參考偵測結果。**`0` 代表「自動識別」，不是「不建立執行緒」。** |
| `threading.auto-worker-thread-cap` | `8` | `1` ~ `64` | 只在 `worker-threads: 0` 時有效：自動識別的上限。刻意保守，不因為機器核心多就建立大量執行緒。 |
| `threading.queue-capacity` | `1024` | `1` ~ `65536` | 有界佇列容量。滿載時新工作被**拒絕**，不會改由呼叫端執行。 |
| `threading.region-tick.enabled` | `false` | **0.1.0 必須為 `false`** | 保留設定。設為 `true` 只會產生警告並被強制關閉。 |

### `cpu`

自動識別在 Windows／macOS 取不到實體核心數與容器配額（Java 標準 API 沒有這個能力）。
這裡可以**由使用者明確宣告**；宣告值一定標示為 `operator-declared`，**不會**偽裝成偵測結果。

| 鍵 | 預設 | 允許值 / 範圍 | 說明 |
| --- | --- | --- | --- |
| `cpu.logical-processors` | `0` | `0` ~ `4096` | `0` = 自動識別。大於 `0` 時**優先於**偵測結果，用來決定自動模式的工作執行緒數。 |
| `cpu.physical-cores` | `0` | `0` ~ `4096` | `0` = 自動識別。只影響診斷顯示，**不會**用來決定工作執行緒數。 |

推導規則（`threading.worker-threads: 0` 時）：

```text
1. cpu.logical-processors > 0            → base = 該宣告值
2. 否則容器 CPU 配額可信                    → base = min(配額, JVM 可用處理器數)
3. 否則                                   → base = JVM 可用處理器數
workers = max(1, min(min(64, auto-worker-thread-cap), base - 1))   # 保留一個處理器給主執行緒
```


### `compatibility`

| 鍵 | 預設 | 允許值 | 說明 |
| --- | --- | --- | --- |
| `compatibility.mode` | `paper` | 僅 `paper` | 0.1.0 不支援其他相容模式。 |
| `compatibility.thread-checks` | `true` | `true` / `false` | 是否啟用 `ThreadSafetyGuard`。開發／測試建議 `true`；正式環境可設 `false` 以省下少量開銷。 |

### `metrics` 與 `diagnostics`

| 鍵 | 預設 | 允許值 / 範圍 | 說明 |
| --- | --- | --- | --- |
| `metrics.enabled` | `true` | `true` / `false` | 是否輸出診斷摘要。指標不會每 Tick 產生大量日誌，而是依取樣間隔彙整。 |
| `metrics.sample-interval-seconds` | `10` | `1` ~ `3600` | 摘要輸出間隔。 |
| `diagnostics.log-worker-errors` | `true` | `true` / `false` | 背景工作發生例外時是否記錄完整堆疊。 |
| `diagnostics.log-queue-overflow` | `true` | `true` / `false` | 佇列滿載（工作被拒絕）時是否記錄。 |

### 驗證行為

- **不合法或超範圍的值會讓 OrigCore 停用**，並在主控台印出明確錯誤（含設定鍵、目前值、允許範圍／允許值）。
- 伺服器本身仍會正常啟動，行為等同未修改的 Paper；修正設定後重新啟動即可。
- 舊開發版本的 `threading.mode` 與 `threading.manual-worker-threads` 已移除；若仍存在會產生明確警告（不會默默忽略）。
- `threading.enabled: false` 會產生警告，並明確說明不會建立背景工作池。
- 系統屬性 `-Dorigcore.thread-check.mode=off|warn|strict` 可覆寫 `compatibility.thread-checks`。

---

## 診斷輸出

依 `metrics.sample-interval-seconds` 間隔輸出一次摘要（預設每 10 秒；**不是**每 Tick）：

```
===== OrigCore diagnostics summary #2 =====
  sample interval  : 10.0 s (202 ticks). TPS and average tick time come from the Paper API.
  TPS 1m/5m/15m    : 19.98 / 19.98 / 19.98
  MSPT (OrigCore)  : P50 0.42 ms / P95 1.62 ms / P99 10.19 ms (samples 202)
  Paper avg tick   : 0.43 ms
  main-thread time : P50 0.42 ms (measured by OrigCore; same samples as MSPT)
  worker threads   : 8 (busy 0)
  work queue       : 0 / 1024
  work counters    : submitted 0, completed 0, cancelled 0, rejected 0, failed 0, timeouts 0
  queue latency    : P50 0.0 us / P95 0.0 us / P99 0.0 us
  execution time   : P50 0.0 us / P95 0.0 us / P99 0.0 us
  main dispatch    : dispatched 0, executed 0, rejected 0, buffered 0
  safety violations: 0 (mode WARN)
  NOTE             : worker pool utilisation is NOT machine-wide CPU usage, and TPS is not the only performance metric.
```

關閉時另外輸出最終統計，並把完整診斷快照寫入 **`logs/origcore-diagnostics.txt`**（回報問題時請附上這個檔案）。

### MSPT 的兩種來源

- `MSPT (OrigCore)`：OrigCore 自己在 Tick 進入點與結束點量測的「Tick 本體耗時」。
- `Paper avg tick`：Paper 透過 `Bukkit#getAverageTickTime()` 提供的數值。

兩者都輸出，方便交叉比對；定義不同，數值不完全相同是正常的。

---

## 專案結構

```
paper-server/src/main/java/io/origcore/
  OrigCore.java                     核心常數與版本來源（讀取 JAR manifest）
  OrigCoreBootstrap.java            啟動 / Tick / 關閉掛鉤
  config/  OrigCoreConfig            設定載入、驗證與預設檔產生
           OrigCoreConfigException
  cpu/     CpuResourceDetector       CPU 資源偵測（純 JDK，取不到就回報未知）
           CpuResourceInfo
  metrics/ DiagnosticsReporter       診斷指標收集與摘要輸出
  thread/  WorkerPool                固定大小執行緒池 + 有界佇列
           DefaultWorkScheduler      工作排程與取消語意
           MainThreadDispatcher      非阻塞派送回主執行緒
           ThreadSafetyGuard         執行緒安全違規偵測
           ThreadCoordinator         統一生命週期管理
           WorkTask / WorkHandle / WorkTaskResult / WorkTaskState / WorkQueueMetrics
  util/    LatencyWindow             定長環狀緩衝區與百分位數

paper-server/src/origcoreTest/java/io/origcore/
  config/OrigCoreConfigTest.java         設定驗證（14 項）
  cpu/CpuResourceDetectorTest.java       CPU 推導不變條件（6 項）
  thread/WorkerPoolTest.java             執行緒池與有界佇列語意（6 項）
  thread/DefaultWorkSchedulerTest.java   提交／取消／拒絕／關閉語意（6 項）
  util/LatencyWindowTest.java            百分位數計算（6 項）

paper-server/patches/sources/net/minecraft/server/MinecraftServer.java.patch
                                       OrigCore 唯一的 Minecraft 層 patch（3 個 hook）

docs/                                  架構、建置、相容性、效能量測、區域化 Tick 藍圖
tools/smoke-test.ps1                   啟動／關閉驗證腳本
```

---

## 分支策略

| 分支 | 用途 |
| --- | --- |
| `main` | 穩定分支。只包含已驗證的內容。 |
| `exp/region-tick` | 區域化 Tick 實驗分支。**未經驗證不得合併回 `main`。** |
| `release/0.1.0` | 0.1.0 的發布標籤。 |

只維護 Minecraft 1.21.11。不建立多版本矩陣。

---

## 版本與上游對應

| 屬性 | 值 |
| --- | --- |
| `OrigCore-Version` | `0.1.0`（來源：`gradle.properties` 的 `origCoreVersion`） |
| JAR 檔名版本 | `1.21.11-R0.1-SNAPSHOT`（= `project.version`，Minecraft／Bukkit API 版本） |
| `Bukkit#getBukkitVersion()` | `1.21.11-R0.1-SNAPSHOT` |
| 上游 commit | `c5eb0790f199da6c38d0a650e1e5cd5415b28185` |

> **不要**把 `gradle.properties` 的 `version` 改成 OrigCore 的版本號。它會經由 `paper-api` 的 `apiVersioning.json`
> 變成 `Bukkit#getBukkitVersion()` 的回傳值；改掉會讓插件判斷錯版本或拒絕載入。
> OrigCore 自己的版本請改 `origCoreVersion`。

---

## 語言慣例

| 位置 | 語言 | 原因 |
| --- | --- | --- |
| 文件、`README` | 繁體中文 | 主要讀者語言。 |
| `config/origcore.yml` 註解 | 繁體中文 | 設定檔以 UTF-8（無 BOM）寫入，任何編輯器都能正確顯示。 |
| 程式碼註解、Javadoc | 繁體中文 | 原始碼為 UTF-8。 |
| 類別名稱、方法名稱、變數 | 英文 | 可讀性與一致性。 |
| **執行期日誌與例外訊息** | **ASCII 英文** | Paper 的 `log4j2.xml` 未指定 charset；在非 UTF-8 環境（例如 Windows 代碼頁 936）下寫入中文會被寫壞。為了讓日誌在任何環境都可讀、可 grep，執行期訊息一律使用 ASCII。 |

---

## 文件

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — 架構、執行緒邊界、不變條件、失敗安全設計。
- [`docs/BUILD.md`](docs/BUILD.md) — 完整建置流程、patch 工作流程、疑難排解。
- [`docs/COMPATIBILITY.md`](docs/COMPATIBILITY.md) — 相容性範圍與明確的不保證事項。
- [`docs/BENCHMARKS.md`](docs/BENCHMARKS.md) — 可重現的效能量測方法（**不包含未經量測的效能宣稱**）。
- [`docs/REGION_TICK_ROADMAP.md`](docs/REGION_TICK_ROADMAP.md) — 區域化 Tick 的後續規劃（R001–R006）。

---

## 授權

OrigCore 繼承上游授權，詳見 [`LICENSE.md`](LICENSE.md)。OrigCore 自身的修改同樣以 GPLv3 釋出。

OrigCore 是 Paper 的衍生專案，**不是** PaperMC 的官方產品。請不要向 PaperMC 回報 OrigCore 的問題。

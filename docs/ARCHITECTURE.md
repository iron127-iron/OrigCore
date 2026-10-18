# OrigCore 架構

本文件說明 OrigCore 0.1.0 的實際設計、執行緒邊界與不變條件。
文中描述的一切都對應到 `paper-server/src/main/java/io/origcore/` 中真實存在的程式碼，
沒有規劃中卻被描述成已完成的功能。

---

## 1. 目標與非目標

### 目標

1. **以 Paper 為基底**，沿用上游的 patch / 建置機制，讓上游升級仍然是可行的。
2. **把背景工作的邊界做對**：什麼可以放到背景、什麼絕對不行、失敗時會怎樣。
3. **可觀察**：任何效能主張都必須能用量測資料支持。
4. **誠實**：未實作的功能一律標示為未實作，不以名稱暗示能力。

### 非目標（0.1.0）

- 不把 Minecraft 世界 Tick 平行化。
- 不移植 Folia 的區域化架構。
- 不為了「看起來支援多核心」而建立未受管理的執行緒池。
- 不支援 1.21.11 以外的 Minecraft 版本。
- 不改變 Bukkit／Spigot／Paper 事件與排程器的既有執行緒語意。

---

## 2. 上游整合策略

OrigCore **不是**把 Paper 原始碼複製到獨立專案，而是直接沿用 Paper 專案本身：

- 以上游 `ver/1.21.11` 分支（commit `c5eb0790f199da6c38d0a650e1e5cd5415b28185`）為基底。
- 上游的修改以 paperweight 的 patch 堆疊表示（914 個 source patches、6 個 resource patches）。
- OrigCore 的修改採兩種方式：
  1. **直接編輯 Paper 自己的檔案**（例如 `paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java`）。
     這類路徑由 patch 堆疊還原，因此不需要新增 patch 檔案。
  2. **擴充既有的 Minecraft 層 patch**：OrigCore 只在
     `paper-server/patches/sources/net/minecraft/server/MinecraftServer.java.patch` 內加入 3 個 hook，
     沒有刪除或重排上游檔案。

這樣做的理由：上游升級時，衝突只會集中在極少數位置，維護成本可預期。

### 唯一的 Minecraft 層修改

`MinecraftServer.java` 中加入三個呼叫點：

| Hook | 位置 | 用途 |
| --- | --- | --- |
| `OrigCoreBootstrap.onServerTickStart()` | `processPacketsAndTick` 開頭 | 記錄 Tick 起始時間 |
| `OrigCoreBootstrap.onServerTick()` | `processPacketsAndTick` 結尾 | 記錄 Tick 結束、排空暫存的派送工作、輸出摘要 |
| `OrigCoreBootstrap.onServerStopping()` | `stopServer()` 中 `"Stopping server"` 之後 | 受控關閉 |

加上 `PaperBootstrap.boot()` 中的 `OrigCoreBootstrap.onServerBoot();`（在伺服器建立之前）。

---

## 3. 元件總覽

```
                        ┌───────────────────────────────┐
   Paper / Minecraft    │  PaperBootstrap.boot()        │
   主流程                │  MinecraftServer              │
                        │    onServerTickStart/onTick   │
                        │    onServerStopping           │
                        └───────────────┬───────────────┘
                                        │ （全部在主執行緒）
                                        ▼
                        ┌───────────────────────────────┐
                        │  OrigCoreBootstrap            │ 失敗安全外層：
                        │  （try/catch，連續失敗自動停用）│ 任何例外都不得影響伺服器
                        └───────────────┬───────────────┘
                                        ▼
                        ┌───────────────────────────────┐
                        │  ThreadCoordinator            │ 唯一建立背景執行緒的地方
                        │   ├─ WorkerPool               │ 固定大小 + 有界佇列
                        │   ├─ DefaultWorkScheduler     │ 提交 / 取消 / 統計
                        │   ├─ MainThreadDispatcher     │ 非阻塞派送回主執行緒
                        │   └─ DiagnosticsReporter      │ 摘要只在關閉時輸出
                        └───────────────────────────────┘
```

輔助元件：`OrigCoreConfig`（設定驗證）、`CpuResourceDetector` / `CpuResourceInfo`（資源偵測）、
`ThreadSafetyGuard`（違規偵測）、`LatencyWindow`（百分位數）。

---

## 4. 生命週期

| 階段 | 呼叫者 | 動作 |
| --- | --- | --- |
| 啟動 | `PaperBootstrap.boot()` | 印出品牌橫幅 → 載入並驗證設定 → 偵測 CPU → 設定 `ThreadSafetyGuard` → `ThreadCoordinator.start()` → 註冊 JVM 關閉掛鉤。 |
| Tick 進入 | `MinecraftServer.processPacketsAndTick` | `onServerTickStart()`：只記錄奈秒時間戳。 |
| Tick 結束 | 同上 | `onServerTick()`：排空啟動前暫存的派送工作 → 量測 Tick 耗時 → 視間隔輸出摘要。 |
| 關閉 | `MinecraftServer.stopServer()` | `onServerStopping()`：停止接受派送 → 停止接受新工作 → 有限時間內讓已開始的工作完成 → 將仍在佇列中的工作標記為拒絕 → 輸出**唯一一次**完整摘要 → 寫出 `logs/origcore-diagnostics.txt`。 |
| 備援關閉 | JVM shutdown hook | 若 `stopServer()` 沒跑到，仍會嘗試關閉工作池。 |

### 失敗安全

- `OrigCoreBootstrap` 的每個掛鉤都以 `try/catch` 包住。
- Tick 掛鉤連續失敗 20 次會**自動停用診斷掛鉤**，避免日誌洪水拖垮伺服器；伺服器本身的 Tick 行為不受影響。
- 設定不合法時 OrigCore **整體停用**（不建立工作池、不做診斷），但伺服器照常啟動，行為等同未修改的 Paper。

---

## 5. 執行緒模型與邊界

### 主執行緒（Minecraft Server thread）

- Minecraft 世界狀態、實體、方塊、背包、事件、Bukkit／Paper API 的呼叫。
- 不變條件：OrigCore **不會**把世界 Tick 從這個執行緒移走。

### 背景工作執行緒（`OrigCore-Worker-N`）

- 只執行「明確可與世界狀態分離的純計算」。
- 全部是 daemon 執行緒（安全網：即使關閉流程本身出錯，也不會讓行程無法結束）。
- 名稱固定為 `OrigCore-Worker-<n>`，方便在執行緒傾印中辨識。

### 兩者之間

- 背景 → 主執行緒：只能透過 `MainThreadDispatcher`（單向、非阻塞）。
- 主執行緒 → 背景：`WorkScheduler#submit`（回傳 `WorkHandle`，不等待）。
- **禁止**主執行緒阻塞等待背景工作：`submitAndWait` 在主執行緒上會直接丟出 `IllegalStateException`。

### 可以被當成「多核心」的部分，與不可以的部分

| 可以 | 不可以 |
| --- | --- |
| 建立固定數量的背景執行緒處理純計算 | 把世界 Tick 平行化 |
| 用量測資料證明背景工作確實平行執行 | 用「核心數很多」推論出「伺服器會變快」 |
| 用 `ConcurrentHashMap` 保護 OrigCore **自己的**統計資料 | 用 `ConcurrentHashMap` 當成世界狀態已具備執行緒安全 |

---

## 6. 工作池：`WorkerPool`

- **固定大小**：`corePoolSize == maximumPoolSize`，不會隨負載無限制建立執行緒。
- **有界佇列**：`ArrayBlockingQueue(queueCapacity)`。
- **拒絕策略**：`ThreadPoolExecutor.AbortPolicy`。
  - **絕對不使用 `CallerRunsPolicy`**。呼叫端可能是 Minecraft 主執行緒，讓它代跑工作會直接破壞 Tick 時間。
- **不使用 `allowCoreThreadTimeOut`**：讓固定大小的行為可預期。
- **忙碌數統計**：覆寫 `beforeExecute` / `afterExecute` 維護 `busyWorkers`。
- **關閉**：先 `shutdown()` 讓已排入的工作完成；逾時才 `shutdownNow()`，並回報**從未開始執行**的工作清單。
  - 這份清單由 `DefaultWorkScheduler` 標記為 `REJECTED`，因此在統計上不會「消失」。

---

## 7. 工作生命週期與取消語意

```
QUEUED ──► RUNNING ──► SUCCESS
   │           └─────► FAILED
   ├──► CANCELLED      （僅能在尚未開始執行前取消）
   └──► REJECTED       （佇列已滿、工作池已關閉，或伺服器關閉）
```

關鍵保證：

- `cancel()` 只有在 `QUEUED -> CANCELLED` 的 CAS 成功時才回傳 `true`。
  **不會**出現「回報已取消，但其實還在執行」的情況。
- 工作開始時以 `QUEUED -> RUNNING` 的 CAS 搶佔；搶不到（已被取消或拒絕）就**不執行** `compute()`。
- 呼叫端等待逾時（`WorkTaskResult.timeout`）**不是**工作的終止狀態；工作本身可能仍在執行，統計上仍會計入完成或失敗。

---

## 8. `MainThreadDispatcher`

設計要求與對應作法：

| 要求 | 作法 |
| --- | --- |
| 不阻塞等待背景任務 | 本類別沒有任何阻塞方法。 |
| 不從背景執行緒同步等待主執行緒結果 | 只提供單向派送（`dispatch`），沒有「等待主執行緒回覆」的 API。 |
| 使用 Paper 既有機制 | 直接呼叫 `MinecraftServer#execute(Runnable)`，也就是 Minecraft 主執行緒既有的工作佇列，不另外建立事件迴圈。 |
| 已經在主執行緒時的行為 | 直接執行，不排隊、不阻塞（同一個執行緒，沒有順序問題）。 |
| 極早期階段（伺服器尚未建立） | 暫存在本機佇列，於第一次 Tick 排空；佇列有上限（避免無限制成長），滿了就拒絕並記錄。 |
| 關閉處理 | 關閉後不再接受派送；尚未進入主執行緒佇列的暫存工作會被丟棄並**記錄數量**，而不是在主執行緒停止後繼續修改世界。已用 Paper 主執行緒佇列派送的工作由 Paper 自己的關閉流程負責。 |

---

## 9. `ThreadSafetyGuard`

用途：在開發與測試環境中找出「背景工作誤觸主執行緒專屬資源」的違規。

- 模式：`OFF` / `WARN` / `STRICT`，可由設定檔或 `-Dorigcore.thread-check.mode=...` 覆寫。
- 違規報告包含：違規類型、目前執行緒（名稱與 `threadId`）、主執行緒名稱、累計違規次數、呼叫堆疊（最多 12 行）。
- **關於「區域／世界資訊」**：違規通常發生在非主執行緒，此時讀取世界或區域狀態本身就不是執行緒安全的行為。
  OrigCore **刻意不去讀取**，並在報告中明確標示「intentionally not read」。這是設計決定，不是缺陷。
- **效能**：檢查本身只做執行緒比較，成本極低。`STRICT` 模式會產生例外堆疊與日誌，成本較高，因此僅建議用於開發與測試；正式環境使用 `OFF` 或 `WARN`。

---

## 10. CPU 資源偵測與工作執行緒數推導

「核心數」的設定入口只有一個：`threading.worker-threads`。

- `0` = 自動識別（預設）
- `1` ~ `64` = 固定使用這個數量，**不參考**偵測結果

`CpuResourceInfo` **刻意分開存放**不同來源的處理器數量，不讓它們互相冒充：

| 欄位 | 來源 | 取不到時 |
| --- | --- | --- |
| `physicalCores` | Linux `/proc/cpuinfo`（`physical id` + `cpu cores`） | `UNKNOWN` |
| `osLogicalProcessors` | Linux `/proc/cpuinfo` 的 `processor` 行數；其他平台退回 `OperatingSystemMXBean` | `UNKNOWN` |
| `jvmAvailableProcessors` | `Runtime#availableProcessors()` | 至少 1 |
| `containerQuotaCpus` | cgroup v2 `cpu.max` → cgroup v1 `cpu.cfs_quota_us` / `cpu.cfs_period_us` | `UNKNOWN` |
| `declaredLogicalProcessors` | `cpu.logical-processors`（使用者宣告；**不經偵測**） | `NOT_DECLARED`（= 0） |
| `declaredPhysicalCores` | `cpu.physical-cores`（使用者宣告；**不經偵測**） | `NOT_DECLARED`（= 0） |
| `effectiveWorkerThreads` | 由下列公式推導 | — |

使用者宣告值**不會覆蓋**任何偵測欄位：兩者並列顯示，宣告值在日誌中標示為 `operator-declared`，
因此「偵測到的」與「使用者填的」永遠分得清楚。

推導方式（`CpuResourceDetector#computeEffectiveWorkerThreads`）：

```
workerThreads > 0 ： min(workerThreads, hardMax)        // 固定值，不呼叫偵測

workerThreads == 0： base = declaredLogicalProcessors   若 > 0
                          else (containerQuota 可信) ? min(containerQuota, jvmAvailable)
                          else jvmAvailable
                    cap  = min(hardMax, autoWorkerThreadCap)   // 預設 hardMax = 64, autoWorkerThreadCap = 8
                    workers = max(1, min(cap, base - 1))       // 保留一個處理器給主執行緒
```

`cpu.physical-cores` **不參與**這個推導：Java 標準 API 無法驗證它，用它推導等於把使用者輸入
當成硬體事實。它只出現在診斷輸出中。

`autoWorkerThreadCap` 刻意保守：OrigCore 不主張「核心越多就越快」。

限制與誠實聲明（也會寫進啟動日誌）：

- Windows／macOS 的 Java 標準 API 未提供實體核心數與容器配額，因此一律回報「未知」並說明原因，
  OrigCore **不會**用邏輯處理器數冒充實體核心數，也不會用猜測值代替容器配額。
- 使用者宣告值只是「使用者說的事實」，OrigCore 不驗證、也不宣稱它是硬體實測值。
- `worker-threads=0` 是「自動識別」，**不是**「不建立執行緒」。
- 工作執行緒數**不代表** Minecraft 世界狀態可以平行處理。

---

## 11. 診斷指標

`DiagnosticsReporter`：

- 每 Tick 只做兩件低成本的事：一次時間相減，與一次環狀緩衝區寫入（`LatencyWindow`）。
- **0.1.0 不輸出週期性摘要**：不再依固定取樣間隔自動印出摘要，
  只在伺服器關閉時輸出**唯一一次**完整摘要（外加一行 `OrigCore final stats` 精簡統計）。
  長時間執行時不會被摘要洗版，關閉時的完整內容也會寫入 `logs/origcore-diagnostics.txt`。
- 除錯用（**預設關閉**）：JVM 系統屬性 `-Dorigcore.diagnostics.periodic=true` 改成每 60 秒輸出一次，
  判斷邏輯獨立成可單元測試的 `PeriodicSummaryPolicy`（純函式 `isDue(now, lastReport)`）。
  其他任何值（含 `false`、無法辨識的字串）都維持「不輸出」，啟動日誌會明確印出實際生效的狀態。
- 取樣視窗 `TICK_SAMPLE_WINDOW = 1200`（約 60 秒 @ 20 TPS），避免無上限成長；
  摘要會列出實際樣本數，並說明樣本只涵蓋最近 1200 個 Tick（不是整段執行期間）。
- 輸出欄位：TPS（1m/5m/15m，來自 `Bukkit#getTPS()`）、MSPT（OrigCore 自量，P50/P95/P99）、
  `Paper avg tick`、主執行緒耗時、工作執行緒數與忙碌數、佇列長度、提交／完成／取消／拒絕／例外／逾時計數、
  佇列等待與執行耗時百分位數、主執行緒派送統計、執行緒安全違規數、摘要策略（週期摘要開或關）。
- `metrics.enabled: false` 時不取樣，因此摘要改為明確說明「未收集樣本、TPS/MSPT 不列出」，
  但仍然輸出精確的工作計數器——不會把「沒量到」印成 0 當成有量到的數字。
- 摘要中固定附上一行註記：**工作池忙碌率不等於整台機器的 CPU 使用率；TPS 也不是唯一效能指標。**

### 為什麼 MSPT 有兩個來源

| 名稱 | 量測方式 | 意義 |
| --- | --- | --- |
| `MSPT (OrigCore)` | OrigCore 在 Tick 進入點／結束點之間量測 | Tick 本體耗時（與 Paper 的定義可能略有差異） |
| `Paper avg tick` | `Bukkit#getAverageTickTime()` | Paper 自己的平均值 |

兩者並列輸出，方便交叉比對；數值不完全相同是正常的。

### 日誌格式的刻意選擇

多行日誌訊息在並行輸出下可能與其他日誌交錯，因此品牌橫幅與診斷摘要都採**逐行輸出**，
確保順序穩定。此外，所有執行期日誌與例外訊息一律使用 **ASCII 英文**：Paper 的 `log4j2.xml`
未指定 charset，在非 UTF-8 環境（例如 Windows 代碼頁 936）下寫入中文會被寫壞。
設定檔註解、程式碼註解與文件仍使用繁體中文。

---

## 12. 不變條件（Invariants）

以下每一項都有對應的單元測試或程式碼結構保證：

1. 同一個工作只會有一次終止轉移（`QUEUED -> {RUNNING, CANCELLED, REJECTED}`）。
2. 被取消或被拒絕的工作**不會**執行 `compute()`。
3. 佇列滿載時**不會**使用呼叫端執行緒代跑。
4. 工作池的執行緒數是固定的，不會隨負載成長。
5. 工作執行緒全部是 daemon。
6. 設定值超出範圍時**丟出例外**，不沉默回退。
7. `threading.region-tick.enabled` 永遠是 `false`。
8. 主執行緒不會阻塞等待背景工作。
9. OrigCore 的診斷或關閉錯誤**不會**阻止伺服器啟動或結束。
10. 工作的忙碌數在結束後回到 0。

---

## 13. 已知限制

- 0.1.0 **沒有**任何原版工作被移到背景執行；工作池目前沒有內建的使用者。這是刻意的：
  先建立正確的邊界與可量測的基礎，再考慮放入哪些工作。
- `physicalCores` 與 `containerQuotaCpus` 在 Windows／macOS 一律為「未知」。
- 診斷的「主執行緒耗時」與 MSPT 使用同一組樣本，因此兩者的 P50 必然相同。
- 週期性摘要的除錯路徑（`-Dorigcore.diagnostics.periodic=true`）目前只有單元測試覆蓋，
  **尚未在實際伺服器上跑過**；實際在伺服器上驗證過的是預設（不輸出週期摘要）路徑。
- 沒有熱重載設定；修改設定需重新啟動。
- `region-tick` 為保留設定，無實際作用。

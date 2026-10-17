# 效能量測

本文件說明**如何可重現地量測** OrigCore 的效能，以及目前已觀察到的資料。

> ## 誠實聲明
>
> **OrigCore 0.1.0 不主張任何效能提升。**
>
> 0.1.0 沒有任何原版（vanilla）工作被移到背景工作池執行——工作池目前沒有內建使用者。
> 因此與未修改的 Paper 1.21.11 相比，OrigCore 0.1.0 應視為**效能中立**。
>
> 本專案**不會**發布沒有量測方式、沒有環境資訊、無法重現的效能數字。
> 任何「核心越多就越快」的說法都不是本專案的主張。

---

## 1. 為什麼不能只看 TPS

TPS 有四捨五入到 20 的天花板，而且對短時間的延遲尖峰很不敏感。
一台 TPS 20 的伺服器可能仍然讓玩家感受到明顯的卡頓。

因此 OrigCore 的診斷同時輸出：

| 指標 | 為什麼需要 |
| --- | --- |
| TPS（1m / 5m / 15m） | 整體吞吐量的粗略指標。 |
| MSPT（P50 / P95 / P99） | 單一 Tick 的耗時分布；P95 / P99 才看得到尖峰。 |
| `Paper avg tick` | 與 Paper 自己的量測交叉比對（定義不同，數值不會完全相同）。 |
| 工作佇列等待時間（P50/P95/P99） | 分辨「工作執行很久」與「工作在排隊」。 |
| 工作執行時間（P50/P95/P99） | 工作本身的成本。 |
| 工作計數（提交／完成／取消／拒絕／例外／逾時） | 拒絕與逾時是**正確性**問題，不只是效能問題。 |
| 主執行緒派送統計 | 背景回到主執行緒的負擔。 |

**工作池忙碌率不等於整台機器的 CPU 使用率。** 診斷摘要中會固定印出這句註記。

---

## 2. 量測方式

### 2.1 準備

```powershell
# 1) 產出可用的 JAR（bundler 變體，含函式庫）
.\gradlew.bat createMojmapBundlerJar

# 2) 準備一個乾淨的執行目錄
Remove-Item -Recurse -Force bench-origcore -ErrorAction SilentlyContinue
```

為了讓量測可重現，請固定以下條件並**記錄下來**：

- 硬體：CPU 型號、實體核心數、邏輯處理器數、RAM。
- JVM：版本與供應商、啟動參數（特別是堆記憶體大小與 GC）。
- 測試世界：是全新產生、還是預先產生好且已存在？
- 是否有玩家在線、是否有插件、插件清單與版本。
- 檢視距離（view-distance）、模擬距離（simulation-distance）。
- 是否有其他行程同時在吃 CPU。

### 2.2 取得資料

1. 啟動伺服器，讓世界產生完成（第一份摘要通常是產生期間的資料，**不要**拿來當基準）。
2. 等 TPS 穩定在 20、且連續數份摘要的 MSPT 波動變小之後再開始記錄。
3. 對照組請用**同一個世界、同樣的 JVM 參數、同樣的插件**執行未修改的 Paper 1.21.11。
4. 每組設定至少重跑 3 次，取中位數；並保留全部原始輸出。
5. 關閉伺服器後保留 `logs/origcore-diagnostics.txt` 作為附件。

原始資料的來源優先順序：

| 來源 | 內容 |
| --- | --- |
| 主控台／`logs/latest.log` | 每次取樣的完整診斷摘要 |
| `logs/origcore-diagnostics.txt` | 關閉時的完整快照（CPU 資訊、工作統計、派送統計） |
| 既有工具 | Spark、Paper 內建 timings 等（OrigCore 不取代它們） |

### 2.3 判讀

- 只看 MSPT 的 **P95 / P99**，不要只看 P50；平均值會把尖峰藏起來。
- 若「工作佇列等待」很高而成「工作執行時間」很低，代表工作太多而不是工作太慢。
- 若有 `rejected` 或 `timeouts` 不為 0，先修正確性，再談效能。
- 阻塞式設定（例如把 `queue-capacity` 調到很小）造成的拒絕**不是**效能改善。

---

## 3. 已觀察到的資料

以下數字來自 0.1.0 的**啟動煙霧測試**（`tools/smoke-test.ps1`），
**不是**效能基準測試，也**沒有**與未修改的 Paper 做對照量測。列出它們只是為了說明診斷輸出的樣子。

### 環境

```
Windows 11
Eclipse Temurin 25.0.4
20 logical processors, ~31.7 GB RAM
OrigCore 0.1.0（Minecraft 1.21.11，Paper upstream ver/1.21.11@c5eb079）
-Xms/-Xmx = 2048 MB
全新產生的世界，沒有玩家，沒有插件
worker-threads: 0（auto）→ 實際上建立 8 條工作執行緒，佇列容量 1024
```

### 摘要

| 摘要 | 涵蓋時間 | Ticks | TPS 1m | MSPT P50 | MSPT P95 | MSPT P99 | Paper avg tick |
| --- | --- | --- | --- | --- | --- | --- | --- |
| #1 | 世界產生期間 | 1 | 15.88 | 62.56 ms | 62.56 ms | 62.56 ms | 62.98 ms |
| #2 | 10.0 s | 202 | 19.98 | 0.42 ms | 1.62 ms | 10.19 ms | 0.43 ms |
| #3 | 10.1 s | 201 | 19.99 | 0.25 ms | 0.46 ms | 0.71 ms | 0.27 ms |
| #4 | 10.0 s | 201 | 19.99 | 0.17 ms | 0.36 ms | 0.49 ms | 0.30 ms |

同一時間的工作統計：

```
worker threads: 8 (busy 0)
work queue    : 0 / 1024
work counters : submitted 0, completed 0, cancelled 0, rejected 0, failed 0, timeouts 0
main dispatch : dispatched 0, executed 0, rejected 0, buffered 0
safety violations: 0 (mode WARN)
```

### 這些數字說明了什麼

1. **摘要 #1 是產生世界期間的資料**（TPS 15.88、MSPT 62 ms），不能當成基準。
2. 世界產生完成後 TPS 回到 19.99，MSPT P50 降到 0.17–0.42 ms，P99 仍有 0.49–10.19 ms——
   **這正是只看 P50 會看不到的東西**。
3. **所有工作計數都是 0。** 這是預期結果，也直接印證了 §0 的聲明：
   0.1.0 沒有任何原版工作被放到背景執行，所以它對效能的影響應該接近零。
4. 這個量測**不足以**支持任何效能主張，也不應該被引用為效能證據。

---

## 4. 對照量測的建議做法

要做真正的對照，請在**同一個世界**上量測兩者：

| 步驟 | Paper 1.21.11（對照組） | OrigCore 0.1.0 |
| --- | --- | --- |
| 1 | 用與 OrigCore 相同的 JVM 參數啟動 | 同左 |
| 2 | 同一個已產生完成的世界 | 同左 |
| 3 | 相同的插件與 `view-distance` / `simulation-distance` | 同左 |
| 4 | 相同的壓力腳本（例如固定數量的實體、固定的紅石機械、固定的區塊載入） | 同左 |
| 5 | 記錄 15 分鐘的 TPS 與 MSPT 百分位數 | 同左 |
| 6 | 重跑 3 次取中位數 | 同左 |

`config/origcore.yml` 中影響量測的設定：

| 設定 | 對量測的影響 |
| --- | --- |
| `metrics.enabled` | 關閉時不會有摘要輸出（也就沒有資料）。 |
| `metrics.sample-interval-seconds` | 取樣間隔；太短會讓摘要次數變多。 |
| `compatibility.thread-checks` | `true`（`WARN`）比 `false`（`OFF`）多一些執行緒比較成本。 |
| `diagnostics.log-worker-errors` / `log-queue-overflow` | 只在有例外／拒絕時才有成本。 |
| `threading.mode: disabled` | 可用來量測「與 Paper 完全相同」的情境。 |

---

## 5. 記錄範本

回報效能問題時請填寫：

```text
OrigCore 版本：
Minecraft 版本：
是否為未修改的 Paper 1.21.11 也能重現：

硬體：
  CPU：
  實體核心 / 邏輯處理器：
  RAM：

JVM：
  版本與供應商：
  啟動參數：

伺服器環境：
  世界：全新產生 / 已存在（多大）
  view-distance / simulation-distance：
  插件清單與版本：
  線上玩家數：
  壓力來源：

config/origcore.yml 的差異（相對於預設）：

量測結果（建議至少 3 次）：
  TPS 1m/5m/15m：
  MSPT P50/P95/P99：
  Paper avg tick：
  work counters（submitted/completed/cancelled/rejected/failed/timeouts）：
  queue latency P50/P95/P99：
  execution time P50/P95/P99：

附件：
  logs/origcore-diagnostics.txt
  logs/latest.log
```

---

## 6. 不要做的事

- **不要**只憑 TPS 判斷效能好壞。
- **不要**把工作池忙碌率當成整台機器的 CPU 使用率。
- **不要**用單次、未記錄環境的數字下結論。
- **不要**引用本文件的煙霧測試數字當成效能提升的證據。
- **不要**把「拒絕 / 逾時計數上升」解讀成效能改善。

# 參與 OrigCore 開發

感謝你有興趣參與。OrigCore 是 Paper 的衍生專案，因此**上游的規則同樣適用**；
本文件補充 OrigCore 自己的規範。

> 回報安全性問題請見 [`SECURITY.md`](SECURITY.md)。
> 架構與設計請見 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

---

## 1. 最重要的原則

1. **誠實。** 不宣稱未實作的功能；不發布沒有量測方式、無法重現的效能數字。
2. **保留 Paper 的主執行緒模型。** 這是最高原則，任何改動都不得破壞它。
3. **不要為了「多核心」而建立未受管理的執行緒。**
4. **上游升級必須保持可行。** 不刪除、不重排上游檔案。
5. **執行期日誌使用 ASCII 英文。** 見 §5。

---

## 2. 開發環境

```powershell
git config --global core.longpaths true    # Windows 必要
$env:GRADLE_OPTS = "-Xmx6g -XX:MaxMetaspaceSize=1g"

.\gradlew.bat applyPatches
.\gradlew.bat :paper-server:compileJava
.\gradlew.bat :paper-server:origcoreTest
```

完整說明見 [`docs/BUILD.md`](docs/BUILD.md)。

---

## 3. 修改程式碼的規則

### 3.1 選擇正確的修改位置

| 想改的東西 | 位置 | 需要 `rebuildPatches` |
| --- | --- | --- |
| OrigCore 自己的程式碼 | `paper-server/src/main/java/io/origcore/` | 否 |
| Paper 自己的 Java 檔案 | `paper-server/src/main/java/io/papermc/...` | 否 |
| 上游（Minecraft / CraftBukkit）檔案 | 編輯 `paper-server/src/minecraft/java/...`（需先 `applyPatches`），再 `rebuildPatches` | **是** |

**不要**直接編輯 `paper-server/src/minecraft/` 就期待改動被保存——重跑 `applyPatches` 會被重設。

### 3.2 Minecraft 層的修改要極度節制

目前 OrigCore 在整個 Minecraft 層只有 **3 個 hook**，全部位於
`MinecraftServer.java.patch`。新增任何一個 hook 前請先問：

- 能不能改成在 Paper 自己的檔案中加入呼叫點？
- 這個 hook 會不會讓上游升級變成手動衝突？
- 這個 hook 的失敗會不會影響伺服器啟動或 Tick？

### 3.3 絕對不要做的事

- 不要把 `Paper` 的原始碼複製到獨立專案。
- 不要刪除或重排上游檔案來迎合自己的結構。
- 不要建立同名卻沒有真正接入核心的「假實作」類別。
- 不要在主執行緒上阻塞等待背景工作。
- 不要用 `CallerRunsPolicy`（呼叫端可能是主執行緒）。
- 不要把世界狀態放進背景執行緒處理。
- 不要用 `ConcurrentHashMap` 當成世界狀態已具備執行緒安全。

---

## 4. 測試

### 4.1 OrigCore 單元測試

放在 `paper-server/src/origcoreTest/java/io/origcore/`，執行方式：

```powershell
.\gradlew.bat :paper-server:origcoreTest
```

這些測試**不需要啟動 Minecraft 伺服器**，因此必須保持：

- 不參考 `net.minecraft.*` 或任何需要執行中伺服器的 API。
- 不依賴特定硬體的數值（只驗證不變條件）。
- 不依賴時間的精確值（避免不穩定的測試）。

### 4.2 什麼時候一定要加測試

| 改動 | 必須加測試 |
| --- | --- |
| 新增或修改設定驗證 | 是 |
| 新增或修改工作池／排程器語意 | 是 |
| 新增或修改取消、拒絕、逾時行為 | 是 |
| 只改文件或註解 | 否 |

### 4.3 啟動驗證

```powershell
.\gradlew.bat createMojmapBundlerJar
powershell -NoProfile -ExecutionPolicy Bypass -File tools\smoke-test.ps1 `
  -Jar paper-server\build\libs\origcore-bundler-1.21.11-R0.1-SNAPSHOT-mojmap.jar `
  -RunDir run-origcore -StartupWaitSeconds 45 -MaxHeapMB 2048
```

結束碼必須是 `0`。

---

## 5. 語言慣例

| 位置 | 語言 |
| --- | --- |
| 文件、`README`、設定檔註解 | 繁體中文 |
| 程式碼註解、Javadoc | 繁體中文 |
| 類別／方法／變數名稱 | 英文 |
| **執行期日誌、例外訊息、設定錯誤訊息** | **ASCII 英文** |

執行期訊息必須是 ASCII 的理由：Paper 的 `log4j2.xml` 未指定 charset，
在非 UTF-8 環境（例如 Windows 代碼頁 936）下寫入中文會被寫壞。
`CpuResourceDetectorTest#descriptionMentionsDeclaredValuesAndStaysAsciiOnly`
與 `OrigCoreConfigTest#workerThreadCountAboveTheHardLimitIsRejected`
會實際檢查這件事，請不要繞過它們。

---

## 6. 程式風格

- Java 21。使用 `final` 參數、`final` 區域變數（與上游風格一致）。
- 不使用 `var`（與上游風格一致）。
- 一個類別一個檔案；檔案內不要有未使用的公開方法。
- 日誌**逐行**輸出，不要用多行訊息（多行訊息在並行輸出下會與其他日誌交錯）。
- 對外行為變更必須更新 `docs/` 中的相關文件。

---

## 7. 提交與 PR

### 提交訊息

```
<區域>: <簡短說明>

<為什麼要這樣改、有什麼取捨>
```

例如：

```
thread: 修正取消後仍可能執行 compute() 的競態

原本在 RUNNING 狀態下仍可能被 cancel() 回報成功，導致「已取消但其實在跑」。
改為只在 QUEUED -> CANCELLED 的 CAS 成功時才回報取消，並補上單元測試。
```

### PR 檢查清單

- [ ] `.\gradlew.bat :paper-server:compileJava` 成功
- [ ] `.\gradlew.bat :paper-server:origcoreTest` 全部通過
- [ ] 若改了 Minecraft 層檔案：已執行 `rebuildPatches`，且 `git diff paper-server/patches` 只有預期的改動
- [ ] 若改了對外行為：已更新 `README.md` 或對應的 `docs/`
- [ ] 沒有新增非 ASCII 的執行期訊息
- [ ] 沒有在主執行緒上阻塞等待背景工作
- [ ] 沒有為了效能而犧牲正確性（尤其是取消／拒絕語意）

### 分支

| 分支 | 用途 |
| --- | --- |
| `main` | 穩定分支，只接受已驗證的內容 |
| `exp/region-tick` | 區域化 Tick 實驗；**未經驗證不得合併回 `main`** |
| `release/*` | 發布標籤 |

**只維護 Minecraft 1.21.11。** 不接受加入其他 Minecraft 版本支援的 PR。

---

## 8. 版本號

| 位置 | 意義 |
| --- | --- |
| `gradle.properties` 的 `origCoreVersion` | OrigCore 版本（可改） |
| `gradle.properties` 的 `version` | Minecraft／Bukkit API 版本（**不可改成 OrigCore 版本號**） |
| `OrigCore.FALLBACK_VERSION` | 與 `origCoreVersion` 保持一致的常數 |

詳見 [`docs/BUILD.md`](docs/BUILD.md) §4。

---

## 9. 效能相關的 PR

任何聲稱效能改善的 PR 必須包含：

1. 可重現的量測方式（依 [`docs/BENCHMARKS.md`](docs/BENCHMARKS.md)）。
2. 完整環境資訊（CPU、JVM、世界、插件、參數）。
3. MSPT 的 **P95 / P99**，不只有平均值或 TPS。
4. 明確列出**沒有改善**或**變差**的情境。

沒有上述資料的效能 PR 會被要求補齊，而不是直接合併。

---

## 10. 上游同步

同步上游時請：

1. 只同步 `ver/1.21.11` 分支。
2. 確認 `OrigCore.UPSTREAM_COMMIT`、`OrigCore-Upstream-Commit` manifest 屬性、
   以及 `docs/` 中的 commit 記錄一致。
3. 重新執行完整驗證（編譯、`origcoreTest`、`rebuildPatches`、煙霧測試）。
4. 若上游改動了 `MinecraftServer.java`，請特別檢查 OrigCore 的 3 個 hook 是否仍在正確位置。

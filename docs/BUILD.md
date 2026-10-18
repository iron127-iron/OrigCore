# 建置 OrigCore

本文件說明如何從原始碼建置 OrigCore 0.1.0，以及如何安全地修改核心程式碼。

---

## 1. 環境需求

| 項目 | 需求 |
| --- | --- |
| JDK | **21**（Paper 的 `options.release = 21`；Gradle toolchain 會處理） |
| Gradle | 不需要另外安裝，使用專案內的 Gradle Wrapper（9.2.0） |
| 記憶體 | 建議至少 6 GB 給 Gradle |
| 網路 | 首次建置需下載 Mache mappings、Spigot build data、Paperclip 等 |
| 作業系統 | Windows / Linux / macOS |

已驗證的環境：

```
Windows 11
Eclipse Temurin JDK 25.0.4（作為 Gradle 執行 JVM）
Gradle Wrapper 9.2.0
Git 2.54.0.windows.1
20 logical processors / ~31.7 GB RAM
```

> 編譯目標固定是 **Java 21**，不要改成 25。`runServer` 之類的執行任務會另外要求 JetBrains Runtime 21。

### Windows 前置設定

```powershell
git config --global core.longpaths true
```

反編譯（Mache decompile）產生的路徑會超過 Windows 預設的 260 字元上限，沒有這個設定會在建置中失敗。

---

## 2. 第一次建置

```powershell
# 建議：避免組態快取／背景常駐造成的資源競爭
$env:GRADLE_OPTS = "-Xmx6g -XX:MaxMetaspaceSize=1g"

# 1) 套用上游 patch 堆疊（會建立 paper-server/src/minecraft 並反編譯）
.\gradlew.bat applyPatches

# 2) 編譯
.\gradlew.bat :paper-server:compileJava

# 3) 單元測試（不需要啟動伺服器）
.\gradlew.bat :paper-server:origcoreTest

# 4) 產出伺服器 JAR
.\gradlew.bat createMojmapBundlerJar createMojmapPaperclipJar
```

Linux / macOS 請把 `.\gradlew.bat` 改成 `./gradlew`。

### 產出物

```
paper-server/build/libs/origcore-bundler-1.21.11-R0.1-SNAPSHOT-mojmap.jar     <- 可獨立啟動
paper-server/build/libs/origcore-paperclip-1.21.11-R0.1-SNAPSHOT-mojmap.jar   <- 可獨立啟動
paper-server/build/libs/paper-server-1.21.11-R0.1-SNAPSHOT.jar                <- 不可獨立啟動
```

- **bundler**：內含伺服器與所需函式庫，直接 `java -jar` 即可。
- **paperclip**：啟動時視需要下載函式庫，檔案較小。
- 兩者都會顯示 `[bootstrap] Loading OrigCore ...`。

> **一定要用 `-mojmap` 變體。** 上游刻意停用了 `reobf` 變體的產生，`createReobfBundlerJar` /
> `createReobfPaperclipJar` 會失敗，這是預期行為，不是你的環境有問題。

---

## 3. patch 工作流程

### 3.1 `applyPatches` 做了什麼

1. 讀取 `paper-server/patches/`（sources 與 resources）並套用到 Minecraft 原始碼。
2. 結果寫入 `paper-server/src/minecraft/java`——**這個目錄是它自己的 git repo**，由 paperweight 管理。
3. 這個目錄已被根目錄的 `.gitignore` 排除。

因此：

- **不要**手動編輯 `paper-server/src/minecraft/` 之後期待改動被保存；重跑 `applyPatches` 會被重設。
- **不要**把 `paper-server/src/minecraft/` 的 `upstream` remote 推出去（它指向本機的 mache 快取）。

### 3.2 兩種修改方式

| 想改的東西 | 怎麼做 | 要不要 `rebuildPatches` |
| --- | --- | --- |
| Paper 自己的 Java 檔案（`paper-server/src/main/java/...`） | 直接編輯。這些檔案由 patch 還原，所以**不需要**新增 patch 檔。 | 不需要 |
| 上游檔案內容（例如 `net.minecraft.server.MinecraftServer`） | 先 `applyPatches`，再編輯 `paper-server/src/minecraft/java/...`，然後 `rebuildPatches`。 | **需要** |

OrigCore 0.1.0 的 Minecraft 層修改只有 `MinecraftServer.java` 中的 3 個 hook，
位於 `paper-server/patches/sources/net/minecraft/server/MinecraftServer.java.patch`。

### 3.3 重新產生 patch 檔

```powershell
.\gradlew.bat rebuildPatches
```

預期輸出（0.1.0）：

```
Rebuilt 6 resource patches
Rebuilt 914 source patches
```

重新產生後請確認：

```powershell
.\gradlew.bat applyPatches           # 從乾淨狀態重新套用
git status --porcelain paper-server/patches
```

`rebuildPatches` 會改寫 patch 檔的 blob hash 與行號。只要被修改的 patch 內容語意相同
（僅 hash／行號位移），這是正常的。

> **注意：** 另有一個 `fixupSourcePatches` 任務，會對整個 patch 堆疊執行
> `git rebase -i --autosquash`，比較脆弱（曾遇到殘留的 `.git/index.lock` 與檔案衝突）。
> 除非你明確知道自己要做什麼，否則請使用 `rebuildPatches`。

### 3.4 不小心把 patch 堆疊弄壞了

`paper-server/src/minecraft/java` 是獨立 repo，有可用的標籤（`ATs`、`Imports`、`Mache`、
`ROOT`、`Vanilla`、`base`、`file`）。復原流程：

```powershell
cd paper-server\src\minecraft\java
git reset --hard <最後一個 feature commit>
git rebase --onto <file 標籤> <舊的 file patches commit> main
cd ..\..\..\..
.\gradlew.bat rebuildPatches
```

---

## 4. 修改 OrigCore 版本號

有兩個不同的版本，**不要混淆**：

| 位置 | 意義 | 改動影響 |
| --- | --- | --- |
| `gradle.properties` 的 `origCoreVersion` | OrigCore 核心版本（`0.1.0`） | 寫入 JAR manifest 的 `OrigCore-Version`，並顯示在啟動橫幅與 `logs/origcore-diagnostics.txt`。 |
| `gradle.properties` 的 `version` | Minecraft／Bukkit API 版本（`1.21.11-R0.1-SNAPSHOT`） | 經由 `paper-api` 的 `apiVersioning.json` 變成 `Bukkit#getBukkitVersion()`。**改掉會讓插件判斷錯版本或拒絕載入。** |

`OrigCore.VERSION` 在執行期優先讀取 JAR manifest 的 `OrigCore-Version`，
讀不到才退回程式碼常數 `FALLBACK_VERSION`（也是 `0.1.0`）。因此正常建置下不會出現不一致；
若你改了 `origCoreVersion`，請一併更新 `OrigCore.FALLBACK_VERSION`。

---

## 5. 驗證建置

```powershell
# 1) 編譯
.\gradlew.bat :paper-server:compileJava

# 2) OrigCore 單元測試（47 項，不需要伺服器）
.\gradlew.bat :paper-server:origcoreTest

# 3) 完整檢查（含掃描 jar 中不該被呼叫的 API，以及 Paper 自己的測試）
.\gradlew.bat check

# 4) 啟動／關閉驗證
.\gradlew.bat createMojmapBundlerJar
powershell -NoProfile -ExecutionPolicy Bypass -File tools\smoke-test.ps1 `
  -Jar paper-server\build\libs\origcore-bundler-1.21.11-R0.1-SNAPSHOT-mojmap.jar `
  -RunDir run-origcore -StartupWaitSeconds 45 -MaxHeapMB 2048
```

`tools/smoke-test.ps1` 成功的判斷：

- 主控台出現 `[bootstrap] Loading OrigCore ...`。
- 主控台出現 `This server is running OrigCore version ... (Implementing API version 1.21.11-R0.1-SNAPSHOT)`。
- 產生 `config/origcore.yml`。
- 出現 CPU 偵測報告與 `OrigCore thread coordinator started: ...`。
- 出現 `===== OrigCore diagnostics summary #N =====`。
- 關閉時出現最終統計，並寫出 `logs/origcore-diagnostics.txt`。
- 行程結束碼為 `0`。

> **關於 `gradlew check`：** 這個任務除了 OrigCore 的單元測試與 `scanJarForBadCalls` 之外，
> 也會執行 Paper 上游自己的測試（`tasks.test` 只執行 `*TestSuite`，且會啟動測試用伺服器、
> 每個測試類別 fork 一個 JVM）。
>
> **目前已在 Windows 上驗證的是 `:paper-server:compileJava`、`:paper-server:origcoreTest`
> 與實際啟動煙霧測試；`check` 這個任務尚未在 Windows 上完整跑過。**
> 如果你需要一個確定可用的驗證門檻，請用上面 1、2、4 三個步驟。

---

## 6. 疑難排解

| 症狀 | 原因與處理 |
| --- | --- |
| `File name too long` / 路徑過長錯誤（Windows） | 執行 `git config --global core.longpaths true` 後重新建置。 |
| `Unable to create ... .git/index.lock` | 先前的 git 操作中斷。確認沒有其他 git 行程在跑，刪除該檔案後重試。 |
| `rebuildPatches` 或 `fixupSourcePatches` 衝突 | 見 §3.4 的復原流程。 |
| 找不到 `createReobf*` 的產出 | 這是預期行為，上游已停用 reobf 變體。請用 `-mojmap` 變體。 |
| 第一次建置很慢 | 需要下載 mappings 並反編譯整個 Minecraft；後續建置會使用快取。 |
| 建置記憶體不足 | 設定 `GRADLE_OPTS="-Xmx6g -XX:MaxMetaspaceSize=1g"`。 |
| `runServer` 找不到 JDK | 該任務要求 JetBrains Runtime 21，會由 Gradle toolchain 自動 provision。 |

### Windows PowerShell 5.1 的兩個陷阱

1. **`.ps1` 檔案必須是 UTF-8 with BOM**。PowerShell 5.1 在沒有 BOM 時會用 ANSI 解讀，
   含有中文的腳本會解析失敗。`tools/smoke-test.ps1` 因此刻意保存為 UTF-8 with BOM。
2. **寫給伺服器讀的檔案（`eula.txt`、`server.properties`）必須是 UTF-8 without BOM**，
   否則第一個鍵會被 BOM 污染。

---

## 7. 分支與 CI

| 分支 | 用途 |
| --- | --- |
| `main` | 穩定分支。 |
| `exp/region-tick` | 區域化 Tick 實驗。未經驗證不得合併回 `main`。 |
| `release/0.1.0` | 0.1.0 發布標籤。 |

OrigCore 自己的遠端：`origin` → <https://github.com/iron127-iron/OrigCore>
（`upstream` 仍然指向 `PaperMC/Paper`，只用來同步上游，**不要 push 到 upstream**）。

CI 位於 `.github/workflows/build.yml`，執行內容：

1. `applyPatches`
2. `:paper-server:compileJava`
3. `:paper-server:origcoreTest`（並上傳測試結果 XML）
4. `rebuildPatches` 後檢查 `git diff -- paper-server/patches` 必須為空
   （確認 patch 堆疊是可重現的）
5. `createMojmapBundlerJar` + `createMojmapPaperclipJar`（並上傳產出）

CI 只建置 Minecraft 1.21.11，**不建立多版本矩陣**，也**不執行 `gradlew check`**
（Paper 自己的測試任務會啟動測試用伺服器，不適合放在這個流程裡）。

### CI 的實際狀態（誠實聲明）

- `.github/workflows/build.yml` 從**未被執行過**。
- GitHub 的行為是：**在同一次 push 中新增的 workflow 不會被那次 push 觸發**。
  0.1.0 首次 push 時 `build.yml` 就是這樣被新增的，所以 Actions 上沒有任何 run。
- 因此第一次 push 之後，請到 Actions →「Build OrigCore」→ Run workflow
  （workflow 已含 `workflow_dispatch`），或再 push 一次任何 commit 來觸發。
- 本專案**沒有**任何「CI 已通過」的紀錄，請不要把「有 CI 檔案」當成「CI 綠燈」。

### 本機是 shallow clone 的坑

建立這個 fork 時用的是 `git clone --depth 1`，導致本機 repo 是 **shallow**，
GitHub 會直接拒絕 push（`remote: fatal: did not receive expected object ...`）。
補救（只需一次，實測約 20 秒、約 60 MB）：

```bash
git fetch --unshallow --no-progress upstream \
  "+refs/heads/ver/1.21.11:refs/remotes/upstream/ver/1.21.11"
```

推上去之後：`git push -u origin main`、`git push origin exp/region-tick`、
`git push origin release/0.1.0`。**不要**用 `git push --all` 或 `--mirror`
（`paper-server/src/minecraft` 是巢狀 repo，它的 remote 指向本機快取）。

### ★把 OrigCore 推上 GitHub 之前請先處理這四個上游 workflow★

`.github/workflows/` 底下除了 `build.yml` 之外，還有四個檔案是 **PaperMC 官方 repo 專用**的
自動化，直接沿用到 fork 會出問題：

| 檔案 | 它原本做什麼 | 在 fork 裡可能的後果 |
| --- | --- | --- |
| `close_invalid_prs.yml` | 由 `pull_request_target` 觸發，關閉不符合規則的 PR | **可能自動關閉你自己 repo 的 PR** |
| `projects.yml` | 把標記 `type: bug` 的 issue 加進 PaperMC 的 project 看板 | 動作錯誤或直接失敗 |
| `publish_pr.yml` | 把 PR 的建置結果發布出去，需要 PaperMC 的 secrets／PAT | 因缺少 secrets 而失敗 |
| `test_results.yml` | 回報測試結果 | 因缺少對應的權限／流程而失敗 |

OrigCore 0.1.0 **刻意沒有刪除**這些檔案：它們是上游檔案，刪掉會讓之後同步
`ver/1.21.11` 時多出無謂的衝突。請在建立 OrigCore 的 GitHub repo 之後，
用 GitHub 介面（Actions → 選擇 workflow → Disable）或直接在 fork 上刪除來處理，
並自行決定是否要長期保留上游檔案以利同步。

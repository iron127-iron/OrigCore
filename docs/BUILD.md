# 建置 OrigCore

本文件說明如何從原始碼建置 OrigCore 0.1.0，以及如何安全地修改核心程式碼。

OrigCore 採用 **一個 Minecraft 版本一個資料夾** 的結構。**所有建置指令都要先進入
你要建置的版本資料夾**（例如 `cd versions\26.2`）。每個資料夾都是一個獨立的
Paperweight 專案，互不影響。版本對照表見 [`../versions/README.md`](../versions/README.md)。

---

## 1. 環境需求

| 項目 | 需求 |
| --- | --- |
| 專案目錄 | **`versions/<版本>/`**（例如 `versions/26.2`）；指令都必須在該目錄內執行 |
| JDK | `1.21.11` → **21**；`26.1`／`26.2`／`26.3` → **25**（對應各版本 `options.release`；Gradle toolchain 會處理） |
| Gradle | 不需要另外安裝，使用各版本資料夾內的 Gradle Wrapper（1.21.11 → 9.2.0；26.1／26.2 → 9.4.1；26.3 → 9.8.0） |
| 記憶體 | 建議至少 6 GB 給 Gradle（每個版本的建置各自需要） |
| 網路 | 首次建置需下載 Mache（含反編譯原始碼）、Minecraft 伺服器 JAR、Paperclip 等 |
| 作業系統 | Windows / Linux / macOS |

已驗證的環境（`versions/1.21.11`）：

```
Windows 11
Eclipse Temurin JDK 25.0.4（作為 Gradle 執行 JVM）
Gradle Wrapper 9.2.0
Git 2.54.0.windows.1
20 logical processors / ~31.7 GB RAM
```

> 1.21.11 的編譯目標固定是 **Java 21**，26.x 是 **Java 25**，不要任意調整。
> `runServer` 之類的執行任務會另外要求 JetBrains Runtime。

### Windows 前置設定

```powershell
git config --global core.longpaths true
```

反編譯（Mache decompile）產生的路徑會超過 Windows 預設的 260 字元上限，沒有這個設定會在建置中失敗。

---

## 2. 第一次建置

```powershell
# 先進入版本資料夾（每個版本都要各自做一次）
cd versions\26.2                 # 或 versions\1.21.11

# 建議：避免組態快取／背景常駐造成的資源競爭
$env:GRADLE_OPTS = "-Xmx6g -XX:MaxMetaspaceSize=1g"

# 1) 套用上游 patch 堆疊（會建立 paper-server/src/minecraft 並反編譯）
.\gradlew.bat applyPatches

# 2) 編譯
.\gradlew.bat :paper-server:compileJava

# 3) 單元測試（不需要啟動伺服器）
.\gradlew.bat :paper-server:origcoreTest

# 4) 產出伺服器 JAR
.\gradlew.bat createBundlerJar createPaperclipJar
```

Linux / macOS 請把 `.\gradlew.bat` 改成 `./gradlew`。

> **任務名稱依版本而異**（因為 Paperweight 版本不同）：
> - `versions/1.21.11`（Paperweight 2.0.0-beta.19）：`createMojmapBundlerJar`／`createMojmapPaperclipJar`。
> - `versions/26.1`、`26.2`（beta.21）、`26.3`（beta.24）：`createBundlerJar`／`createPaperclipJar`。
>   這幾版上游已不再產生 `createMojmap*`／`createReobf*` 變體，bundler 本身就是 mojmap 命名空間。

### 產出物

產物位於**該版本資料夾**的 `paper-server/build/libs/`：

```
# versions/1.21.11
paper-server/build/libs/origcore-bundler-1.21.11-R0.1-SNAPSHOT-mojmap.jar     <- 可獨立啟動
paper-server/build/libs/origcore-paperclip-1.21.11-R0.1-SNAPSHOT-mojmap.jar   <- 可獨立啟動
paper-server/build/libs/paper-server-1.21.11-R0.1-SNAPSHOT.jar                <- 不可獨立啟動

# versions/26.2（26.1／26.3 同理，只有版本號不同）
paper-server/build/libs/origcore-bundler-26.2.local-SNAPSHOT.jar              <- 可獨立啟動
paper-server/build/libs/origcore-paperclip-26.2.local-SNAPSHOT.jar            <- 可獨立啟動
```

- **bundler**：內含伺服器與所需函式庫，直接 `java -jar` 即可。
- **paperclip**：啟動時視需要下載函式庫，檔案較小。
- 兩者都會顯示 `[bootstrap] Loading OrigCore ...`。

> **1.21.11 一定要用 `-mojmap` 變體。** 上游刻意停用了 `reobf` 變體的產生，
> `createReobfBundlerJar` / `createReobfPaperclipJar` 會失敗，這是預期行為，不是你的環境有問題。
> 26.x 沒有 mojmap／reobf 的區分。

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

# 2) OrigCore 單元測試（61 項，不需要伺服器）
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
- 出現 `[OrigCore] diagnostics: periodic summaries are DISABLED (0.1.0 default); ...`。
- 關閉時出現 `OrigCore final stats` 與 `===== OrigCore final diagnostics summary =====`（**只有這一次**；
  整個執行期間不應出現任何 `diagnostics summary #N`），並寫出 `logs/origcore-diagnostics.txt`。
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
| 找不到 `createReobf*` 的產出 | `versions/1.21.11` 的預期行為，上游已停用 reobf 變體；請用 `-mojmap` 變體。`versions/26.x` 沒有這些任務。 |
| `Task with name 'createMojmapBundlerJar' not found` | 你跑的是 26.x 版本。Paperweight 2.0.0-beta.21 之後只註冊 `createBundlerJar`／`createPaperclipJar`。 |
| 在 `versions/<版本>` 內建置時說找不到 `.git` | 本結構的 git 工作區在母 repo 根目錄；各版本 `settings.gradle.kts` 已放寬檢查（接受 `../.git`、`../../.git`）。若你把資料夾搬離母 repo，請在該層 `git init`。 |
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

CI 位於 `.github/workflows/build.yml`，以 **版本矩陣** 對每個 `versions/<版本>/`
資料夾各跑一次（`fail-fast: false`，單一版本失敗不會停掉其他版本）：

1. `applyPatches`
2. `:paper-server:compileJava`
3. `:paper-server:origcoreTest`（並上傳測試結果 XML）
4. `rebuildPatches` 後檢查 `git diff --exit-code -- versions/<版本>/paper-server/patches`
   必須為空（確認 patch 堆疊是可重現的）
5. 打包 `createBundlerJar` + `createPaperclipJar`（1.21.11 為 `createMojmap*` 變體；並上傳產出）

矩陣內容：`1.21.11` 用 JDK 21，`26.1`／`26.2`／`26.3` 用 JDK 25；Gradle 由各版本
資料夾內自己的 wrapper 提供（9.2.0 / 9.4.1 / 9.4.1 / 9.8.0）。

CI **不執行 `gradlew check`**（Paper 自己的測試任務會啟動測試用伺服器，不適合放在
這個流程裡）。

### CI 的實際狀態（誠實聲明，2026-10-18 更新）

- GitHub 的行為：**在同一次 push 中新增的 workflow 不會被那次 push 觸發**。
  0.1.0 第一次 push 時 `build.yml` 就是這樣被新增的，所以**那個 push 沒有產生 run**；
  之後任何 push 都會正常觸發（不需要先手動 Run workflow）。
- **實測結果：每次 push 的 CI 都成功**（`ubuntu-latest`）：

  | 觸發 commit | 事件 | 結果 | Run |
  | --- | --- | --- | --- |
  | `05316122e` | push | ✅ success | [39091507636](https://github.com/iron127-iron/OrigCore/actions/runs/39091507636) |
  | `bc7adfaf3` | push | ✅ success | [39092573829](https://github.com/iron127-iron/OrigCore/actions/runs/39092573829) |
  | `eb9d382d9` | push | ✅ success | [39092905689](https://github.com/iron127-iron/OrigCore/actions/runs/39092905689) |

  （之後每次 push 都會觸發 CI；最新結果請以 Actions 頁面為準。）

- 第二次的步驟全部 success：
  Checkout → Set up JDK 21 → Set up Gradle → Apply upstream patch stack → Compile →
  OrigCore unit tests → Upload test results → Verify the patch stack is reproducible →
  Build server jars → Upload server jars。
  產物：`origcore-test-results`（約 5.8 KB）、`origcore-jars`（約 152 MB）。
- 也就是說：**「patch 堆疊可重現」與「OrigCore 單元測試」在 GitHub 的 Linux runner 上也通過了**，
  本專案的建置流程不限於本機 Windows。仍然請以 Actions 頁面的最新結果為準；
  `workflow_dispatch` 已存在，需要時可以手動 Run workflow。

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

---

## 8. 建置後自動複製 JAR（可選）

`paper-server` 有一個 OrigCore 專屬任務 `deployBundlerJar`：每次
`createMojmapBundlerJar` 產出 JAR 之後，自動把該 JAR 複製到指定目錄
（例如 MCSManager 的實例目錄），省去手動複製。

目標目錄由 Gradle 屬性決定，**不會**寫進專案的版控檔案。查詢順序為：

1. `mcsmanagerInstanceDir.<版本資料夾名稱>`（該版本專屬，例如 `mcsmanagerInstanceDir.26.2`）
2. `mcsmanagerInstanceDir`（所有版本共用；`versions/1.21.11` 沿用這一個）

| 設定位置 | 適用 |
| --- | --- |
| `~/.gradle/gradle.properties`（使用者家目錄） | 只有這台機器生效（建議） |
| 專案 `gradle.properties` | 會進入版控，不建議放機器路徑 |
| 命令列 `-PmcsmanagerInstanceDir=...` | 單次覆寫 |

```properties
# ~/.gradle/gradle.properties
# 1.21.11（沿用原本的屬性名）
mcsmanagerInstanceDir=D:/windows/desk/mcsmanager_windows_release/mcsmanager/daemon/data/InstanceData/<1.21.11 實例 ID>
# 其他版本各自指定；沒設定就只印一行說明並略過
mcsmanagerInstanceDir.26.2=D:/.../<26.2 實例 ID>
```

行為：

- 未設定（或空白）：**不複製**，只印出一行說明；CI 與其他開發者不受影響。
- 已設定但目錄不存在：印出**警告**並略過，建置仍然成功（JAR 本身照常產出）。
- 已設定且目錄存在：複製並印出 `[OrigCore] Copied <檔名> to <完整路徑> (<位元組數>)`。
- 檔名**維持原樣**（例如 `origcore-bundler-1.21.11-R0.1-SNAPSHOT-mojmap.jar`），
  不會改名成 `server.jar`，以免覆蓋你放在同一目錄的其他檔案。

也可以單獨執行：`.\gradlew.bat :paper-server:deployBundlerJar`（會先確保 bundler JAR 已產出）。

> 用 `.properties` 檔案時請用**正斜線**：反斜線在 `.properties` 裡是跳脫字元。

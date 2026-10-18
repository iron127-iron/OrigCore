# OrigCore — Minecraft 26.3

這是 OrigCore 針對 **Minecraft 26.3** 的獨立建置專案。它是一個完整的
Paperweight 專案：可以直接在本資料夾執行 `.\gradlew.bat` 建置，不會、也不需要
影響 `versions/` 底下的其他版本。

> **上游分支：** 26.3 是 Paper 目前的最新版本，程式碼在上游 **`main`** 分支
> （Paper 沒有 `ver/26.3` 分支）。本資料夾的上游 commit 為
> `a90108f43cabdf058548f9b27b9319bf7842c49d`，對應 Fill 上 26.3 的最新 build。
> 因此 JAR manifest 的 `OrigCore-Upstream-Branch` 標記為 `main`。

| 項目 | 值 |
| --- | --- |
| Minecraft 版本（`mcVersion`） | `26.3` |
| 上游 | [PaperMC/Paper](https://github.com/PaperMC/Paper)，分支 `main`，commit `a90108f43cabdf058548f9b27b9319bf7842c49d` |
| OrigCore 版本（`origCoreVersion`） | `0.1.0` |
| Bukkit API 版本（`apiVersion`） | `26.3` |
| 建置工具鏈 | Gradle Wrapper 9.8.0、Java toolchain 25（`options.release = 25`） |
| Paperweight | `2.0.0-beta.24` |
| Mache | `io.papermc:mache:26.3+build.1` |
| 產物 | `paper-server/build/libs/origcore-bundler-26.3.local-SNAPSHOT.jar` |

## 建置狀態

見母 repo 的 [`../README.md`](../README.md) 與 [`../versions/README.md`](../versions/README.md)
的狀態表（統一維護，避免各版本 README 互相矛盾）。

## 建置

```powershell
cd versions\26.3

# 第一次必做：取得 Mache 對應並套用上游 patch 堆疊
.\gradlew.bat applyPatches

# OrigCore 專屬單元測試（不需要啟動 Minecraft 伺服器）
.\gradlew.bat :paper-server:origcoreTest

# 產出可啟動的伺服器 JAR
.\gradlew.bat createBundlerJar
```

> 26.x 的上游 Paperweight（2.0.0-beta.21 之後）**只註冊 `createBundlerJar` 與
> `createPaperclipJar`**，沒有 `createMojmap*`／`createReobf*` 變體。

建置後若要自動複製到某個目錄（例如 MCSManager 實例目錄），在
`~/.gradle/gradle.properties` 設定 `mcsmanagerInstanceDir.26.3`（或共用的
`mcsmanagerInstanceDir`），詳見 [`../versions/README.md`](../versions/README.md)。

## 這個資料夾與 Paper 上游的差異（OrigCore 的修改）

除了上游內容之外，本資料夾只多了以下 OrigCore 修改（可與上游
`PaperMC/Paper` 的 `main` 直接比對）：

| 檔案 | 修改內容 |
| --- | --- |
| `settings.gradle.kts` | 放寬 `.git` 檢查（允許 git 工作區在母 repo 根目錄）、`rootProject.name = "origcore"` |
| `gradle.properties` | 新增 `origCoreVersion=0.1.0` 與說明註解 |
| `paper-server/build.gradle.kts` | `bundlerJarName="origcore"`、manifest 品牌與上游版本標記、`origcoreTest` 原始碼集與任務、`deployBundlerJar` 任務 |
| `paper-server/patches/sources/net/minecraft/server/MinecraftServer.java.patch` | OrigCore 唯一的 Minecraft 層 hook |
| `paper-server/src/main/java/io/origcore/**` | OrigCore 核心實作（設定、CPU 偵測、工作池、診斷） |
| `paper-server/src/origcoreTest/java/io/origcore/**` | OrigCore 專屬單元測試 |
| `paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java` | 啟動掛鉤 |
| `paper-server/src/main/java/io/papermc/paper/ServerBuildInfoImpl.java` | 品牌預設值改為 `OrigCore`，同時保留 Paper 品牌相容性 |
| `paper-server/src/main/resources/logo.png` | OrigCore 標誌 |

Java 25、`mcVersion`／`apiVersion`、依賴版本等**都沿用上游 `main` 的設定**。

## 注意事項

- 本資料夾含有上游的 `AGENTS.md`、`CLAUDE.md`、`CONTRIBUTING.md`、`SECURITY.md`
  等檔案，它們是 Paper 上游的內容，規範的是「對 PaperMC/Paper 貢獻」的流程，
  與 OrigCore 無關。
- 因為上游 26.3 位於 `main`，當 Paper 對 `main` 有新的提交時，本資料夾的上游
  基準點就會過時。要更新時請重新從 `upstream/main` 取樹，並把
  `OrigCore-Upstream-Commit` 一併更新。
- **不要**把 `project.version` 改成 OrigCore 的版本號（會影響
  `Bukkit#getBukkitVersion()`）。OrigCore 的版本請改 `origCoreVersion`。
- 執行期日誌一律使用 ASCII 英文（原因見母 repo README 的〈語言慣例〉）。

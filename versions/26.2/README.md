# OrigCore — Minecraft 26.2

這是 OrigCore 針對 **Minecraft 26.2** 的獨立建置專案。它是一個完整的
Paperweight 專案：可以直接在本資料夾執行 `.\gradlew.bat` 建置，不會、也不需要
影響 `versions/` 底下的其他版本。

| 項目 | 值 |
| --- | --- |
| Minecraft 版本（`mcVersion`） | `26.2` |
| 上游 | [PaperMC/Paper](https://github.com/PaperMC/Paper)，分支 `ver/26.2`，commit `3e24be0380996655d0e4c842cd3bc0f1c08b09a3` |
| OrigCore 版本（`origCoreVersion`） | `0.1.0` |
| Bukkit API 版本（`apiVersion`） | `26.2` |
| 建置工具鏈 | Gradle Wrapper 9.4.1、Java toolchain 25（`options.release = 25`） |
| Paperweight | `2.0.0-beta.21` |
| Mache | `io.papermc:mache:26.2+build.1` |
| 產物 | `paper-server/build/libs/origcore-bundler-26.2.local-SNAPSHOT.jar` |

## 建置狀態

見母 repo 的 [`../README.md`](../README.md) 與 [`../versions/README.md`](../versions/README.md)
的狀態表（統一維護，避免各版本 README 互相矛盾）。

## 建置

```powershell
cd versions\26.2

# 第一次必做：取得 Mache 對應並套用上游 patch 堆疊
.\gradlew.bat applyPatches

# OrigCore 專屬單元測試（不需要啟動 Minecraft 伺服器）
.\gradlew.bat :paper-server:origcoreTest

# 產出可啟動的伺服器 JAR
.\gradlew.bat createBundlerJar
```

> 26.x 的上游 Paperweight（2.0.0-beta.21 之後）**只註冊 `createBundlerJar` 與
> `createPaperclipJar`**，沒有 `createMojmap*`／`createReobf*` 變體。
> `bundler` 產物本身就是 mojmap 命名空間。

建置後若要自動複製到某個目錄（例如 MCSManager 實例目錄），在
`~/.gradle/gradle.properties` 設定 `mcsmanagerInstanceDir.26.2`（或共用的
`mcsmanagerInstanceDir`），詳見 [`../versions/README.md`](../versions/README.md)。

## 這個資料夾與 Paper 上游的差異（OrigCore 的修改）

除了上游內容之外，本資料夾只多了以下 OrigCore 修改（可與上游
`PaperMC/Paper` 的 `ver/26.2` 直接比對）：

| 檔案 | 修改內容 |
| --- | --- |
| `settings.gradle.kts` | 放寬 `.git` 檢查（允許 git 工作區在母 repo 根目錄）、`rootProject.name = "origcore"` |
| `gradle.properties` | 新增 `origCoreVersion=0.1.0` 與說明註解 |
| `paper-server/build.gradle.kts` | `bundlerJarName="origcore"`、manifest 品牌與上游版本標記、`origcoreTest` 原始碼集與任務、`deployBundlerJar` 任務 |
| `paper-server/patches/sources/net/minecraft/server/MinecraftServer.java.patch` | OrigCore 唯一的 Minecraft 層 hook（啟動後 Tick 前、Tick 後、`stopServer()` 之前） |
| `paper-server/src/main/java/io/origcore/**` | OrigCore 核心實作（設定、CPU 偵測、工作池、診斷） |
| `paper-server/src/origcoreTest/java/io/origcore/**` | OrigCore 專屬單元測試 |
| `paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java` | 啟動掛鉤（呼叫 `OrigCoreBootstrap.onServerBoot()`） |
| `paper-server/src/main/java/io/papermc/paper/ServerBuildInfoImpl.java` | 品牌名稱預設值改為 `OrigCore`，同時保留對 Paper 品牌識別的相容性 |
| `paper-server/src/main/resources/logo.png` | OrigCore 標誌 |

Java 25、`mcVersion`／`apiVersion`、依賴版本（Mache、Paperclip、Paperweight）等
**都沿用上游 `ver/26.2` 的設定**，OrigCore 不自行指定。

## 注意事項

- 本資料夾含有上游的 `AGENTS.md`、`CLAUDE.md`、`CONTRIBUTING.md`、`SECURITY.md`
  等檔案，它們是 **Paper 上游的內容**，規範的是「對 PaperMC/Paper 貢獻」的流程。
  那些規則與 OrigCore 無關；OrigCore 的規範請看母 repo 的
  [`../README.md`](../README.md) 與 [`../docs/`](../docs/)。
- **不要**把 `project.version`（`26.2.local-SNAPSHOT`）改成 OrigCore 的版本號。
  它會經由 `paper-api` 變成 `Bukkit#getBukkitVersion()` 的回傳值。OrigCore 的版本
  請改 `origCoreVersion`。
- 執行期日誌一律使用 ASCII 英文（原因見母 repo README 的〈語言慣例〉）。

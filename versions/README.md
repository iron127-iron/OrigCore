# 版本資料夾（`versions/`）

OrigCore 從 0.1.0 之後改為 **一個 Minecraft 版本一個資料夾** 的結構。

每個資料夾都是一個**完整、可獨立建置的 Paperweight 專案**（有自己的 `gradlew`、
wrapper、`paper-server/patches/`、建置腳本與 Paper 原始碼），彼此互不影響：

```
OrigCore/
  README.md            專案總覽（共用）
  docs/                文件（共用）
  tools/               驗證腳本（共用）
  .github/workflows/   CI（共用；以版本矩陣跑各資料夾）
  versions/
    1.21.11/           完整 Paperweight 專案（Minecraft 1.21.11）
    26.1/              完整 Paperweight 專案（Minecraft 26.1.2）
    26.2/              完整 Paperweight 專案（Minecraft 26.2）
    26.3/              完整 Paperweight 專案（Minecraft 26.3）
```

## 為什麼是一個資料夾一個版本，而不是一個分支一個版本

- 你可以在同一台機器上一次 checkout 出所有版本，直接比較、同時建置。
- 每個版本的上游 patch 堆疊、Java 版本、依賴都不同；放在各自的 Gradle 專案裡
  互不干擾，不需要切分支、不需要清快取。
- 共用內容（文件、CI、驗證腳本）留在母 repo 根目錄，不重複四份。

## 各版本對照表

| 資料夾 | Minecraft 版本 | 上游分支 | 上游 commit | Java | Paperweight | Gradle | 建置狀態 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| [`1.21.11/`](1.21.11/) | `1.21.11` | `ver/1.21.11` | `c5eb0790f199da6c38d0a650e1e5cd5415b28185` | 21 | 2.0.0-beta.19 | 9.2.0 | **已驗證**（patch／編譯／測試／打包／啟動） |
| [`26.1/`](26.1/) | `26.1.2` | `ver/26.1.2` | `e4e17fc90d31c3dca6de8bebc87c741749f8f3df` | 25 | 2.0.0-beta.21 | 9.4.1 | 見該資料夾 README |
| [`26.2/`](26.2/) | `26.2` | `ver/26.2` | `3e24be0380996655d0e4c842cd3bc0f1c08b09a3` | 25 | 2.0.0-beta.21 | 9.4.1 | 見該資料夾 README |
| [`26.3/`](26.3/) | `26.3` | `main` | `a90108f43cabdf058548f9b27b9319bf7842c49d` | 25 | 2.0.0-beta.24 | 9.8.0 | 見該資料夾 README |

## 關於資料夾名稱與實際 Minecraft 版本

- **`26.1/` 建置的是 Minecraft `26.1.2`。** Paper 沒有 `ver/26.1` 分支，只有
  `ver/26.1.1` 與 `ver/26.1.2`；資料夾沿用你指定的 `26.1` 名稱，內部
  `gradle.properties` 的 `mcVersion`／`apiVersion` 為 `26.1.2`（即實際上會被建置出來的版本）。
- **`26.3/` 建置的是 Paper `main` 分支。** Paper 沒有 `ver/26.3` 分支；26.3 是
  目前最新版本，程式碼在上游 `main`（commit `a90108f43…`，對應 Fill 上 26.3 的最新
  build）。因此 `26.3/` 的 `OrigCore-Upstream-Branch` 標記為 `main`。

## 建置單一版本

```powershell
cd versions\26.2
.\gradlew.bat applyPatches
.\gradlew.bat :paper-server:origcoreTest
.\gradlew.bat createBundlerJar
```

完整流程、疑難排解與 CI 說明請見 [`../docs/BUILD.md`](../docs/BUILD.md)。

## 建置產物與自動複製

產物位於 `<版本資料夾>/paper-server/build/libs/`，檔名為
`origcore-bundler-<project.version>.jar`（26.x）或
`origcore-bundler-<project.version>-mojmap.jar`（1.21.11，該版 Paperweight 仍有
mojmap／reobf 之分）。

若要在建置後自動複製到某個目錄（例如 MCSManager 的實例目錄），在
`~/.gradle/gradle.properties` 設定（**不要**寫進版控）：

```properties
# 1.21.11（沿用原本的屬性名）
mcsmanagerInstanceDir=D:\\...\\InstanceData\\<1.21.11 的實例>
# 其他版本可各自指定
mcsmanagerInstanceDir.26.2=D:\\...\\InstanceData\\<26.2 的實例>
```

沒設定就只會印一行說明並略過，不會讓建置失敗。

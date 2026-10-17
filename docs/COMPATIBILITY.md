# 相容性

本文件說明 OrigCore 0.1.0 實際保證什麼、不保證什麼。
**如果本文件與其他說明衝突，以本文件為準。**

---

## 1. 支援的 Minecraft 版本

| 項目 | 值 |
| --- | --- |
| 支援的 Minecraft 版本 | **僅 1.21.11** |
| 上游分支 | `ver/1.21.11` |
| 上游 commit | `c5eb0790f199da6c38d0a650e1e5cd5415b28185` |

OrigCore **只維護 1.21.11**，不建立多版本分支或建置矩陣。

理由：Paper 的 patch 堆疊與 Minecraft 內部結構緊密耦合。維護多個版本會讓每個版本都得不到足夠的驗證，
最後變成「每個版本都看起來支援、但沒有一個真的可靠」。

---

## 2. 用戶端版本（連線端）

OrigCore 本身**不處理**用戶端與伺服器之間的版本落差。

- 如果你的使用者需要用舊版用戶端連到 1.21.11 伺服器（或反過來），
  請自行使用 [ViaVersion](https://github.com/ViaVersion/ViaVersion) /
  [ViaBackwards](https://github.com/ViaVersion/ViaBackwards) 之類的外部插件。
- OrigCore **不保證**任何特定用戶端版本可以連線成功，也**不會**為這些外部方案造成的問題負責。
- 本專案的任何文件都**不得**被解讀為「OrigCore 支援所有版本」。

---

## 3. 伺服器端 API 相容性

OrigCore 以「**保留 Paper 的主執行緒模型**」為最高原則。因此：

| 項目 | 保證 |
| --- | --- |
| `Bukkit#getBukkitVersion()` | `1.21.11-R0.1-SNAPSHOT`（與 Paper 相同，插件可正確判斷版本） |
| Bukkit / Spigot / Paper 事件的執行執行緒 | **不變**（仍在主執行緒） |
| 排程器（`BukkitScheduler`）語意 | **不變** |
| `PaperLib` / `ServerBuildInfo` 的品牌檢查 | 見下節 |
| Folia 的區域化 API（`RegionScheduler` 等） | **不支援** |

### 品牌識別與插件相容性

OrigCore 會把核心名稱顯示為 `OrigCore`：

```
This server is running OrigCore version ... (Implementing API version 1.21.11-R0.1-SNAPSHOT)
```

JAR manifest 中：

```
Brand-Id: origcore:origcore
Brand-Name: OrigCore
```

為了避免破壞會檢查品牌的插件，`ServerBuildInfoImpl#isBrandCompatible` **同時接受** Paper 的品牌識別
（`papermc:paper`）。因此：

- 檢查「是否為 Paper」的插件仍會通過。
- 檢查「是否為 Folia」的插件不會通過（OrigCore 不是 Folia）。

### 版本號的兩個層次

| 名稱 | 值 | 用途 |
| --- | --- | --- |
| Minecraft／Bukkit API 版本 | `1.21.11-R0.1-SNAPSHOT` | 插件判斷版本用。**與 Paper 相同。** |
| OrigCore 核心版本 | `0.1.0` | 只在品牌與診斷中出現。 |

---

## 4. 明確的不保證事項

以下事項 OrigCore 0.1.0 **明確不保證**：

1. **不保證任何效能提升。** 0.1.0 沒有任何原版工作被移到背景執行；與未修改的 Paper 相比應視為效能中立。
2. **不保證跨版本相容。** 只支援 1.21.11。
3. **不保證與 Folia 相容。** 沒有實作區域化 API。
4. **不保證所有插件都能運作。** 插件會因為各種原因失敗（例如檢查核心類型、使用 Paper 內部 API、
   依賴特定 build 編號）。OrigCore 保留了 Paper 的事件與排程語意以最大化相容性，但無法保證全部。
5. **不保證 `region-tick` 設定有任何效果。** 它是保留設定，0.1.0 強制 `false`。
6. **不保證設定熱重載。** 修改 `config/origcore.yml` 後需重新啟動。
7. **不保證背景工作可以安全地碰觸任何伺服器狀態。** 見 §5。

---

## 5. 背景工作的相容性邊界

OrigCore 的工作池**只**允許純計算。以下行為在設計上被禁止，也會被 `ThreadSafetyGuard` 標記：

- 修改方塊、世界、實體、玩家背包。
- 呼叫依賴主執行緒的 Bukkit／Paper API。
- 觸發必須在主執行緒觸發的插件事件。
- 讀寫未提供執行緒安全保證的世界資料結構。

需要操作伺服器 API 的後續處理，必須在 `compute()` 完成後透過 `MainThreadDispatcher` 排回主執行緒。

> **`ConcurrentHashMap` 不是世界狀態的執行緒安全證明。** 用執行緒安全的容器包裝一個本質上
> 只能在主執行緒存取的資料結構，並不會讓存取變得安全。

---

## 6. 與其他核心的比較（定位）

| | Paper | Folia | OrigCore 0.1.0 |
| --- | --- | --- | --- |
| 世界 Tick | 單一主執行緒 | 區域化、多執行緒 | **單一主執行緒（與 Paper 相同）** |
| 背景工作 | 上游自行管理 | 區域化排程 | 受控且有限的工作池（目前無內建使用者） |
| 插件事件執行緒 | 主執行緒 | 區域執行緒 | **主執行緒（與 Paper 相同）** |
| 多版本 | 多版本分支 | 跟隨 Paper | **僅 1.21.11** |

OrigCore **不是** Folia 的替代品，也**不是**「更好的 Paper」。它是 Paper 的一個謹慎衍生版本，
重點在於把背景工作的邊界與可觀察性做對。

---

## 7. 回報相容性問題

請附上：

1. `logs/origcore-diagnostics.txt`（OrigCore 關閉時自動產生）。
2. `logs/latest.log`（完整）。
3. 產生問題的插件名稱與版本。
4. 你的 `config/origcore.yml`。
5. 是否能用未修改的 Paper 1.21.11 重現同一個問題。

第 5 點很重要：如果未修改的 Paper 也有一樣的行為，那就不是 OrigCore 的相容性問題。

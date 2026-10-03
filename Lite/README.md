# PlotSquaredLite

PlotSquared 的輕量版，只保留最基礎的地皮功能與權限控制，**直接讀寫 PlotSquared 原本的設定檔與資料庫**，
可以無痛取代 PlotSquared，也可以隨時換回去。

- 不依賴 WorldEdit、FastAsyncWorldEdit 或其他插件
- 只需要 Paper 1.21 以上
- 不打包任何第三方函式庫（SQLite 與 MySQL 驅動由伺服器本身提供），jar 很小

## 功能範圍

| 功能 | 狀態 |
| --- | --- |
| 地皮世界生成（經典道路／圍牆／地皮） | 支援，與 PlotSquared 的計算方式完全相同 |
| 認領、自動認領、刪除、清除 | 支援 |
| 信任者 (trust)、成員 (add)、禁止 (deny) | 支援，規則與 PlotSquared 相同 |
| 家園位置、拜訪其他玩家 | 支援 |
| 建築／破壞／互動保護、液體與活塞跨界、爆炸保護 | 支援 |
| 既有的合併地皮 | 可讀取、保護、清除與刪除（整個群組一起處理） |
| 地皮數量上限 `plots.plot.<數字>` | 支援 |
| 新的合併 (merge)、拆分 (unlink) | 不支援 |
| Flags（pvp、greeting 等） | 不支援，但資料庫中的 flags 會原樣保留 |
| 道路模板 (road schematic)、地皮模板 | 不支援，新區塊只會生成經典地形 |
| 部分地皮區域 (`generator.type: 2`)、地皮叢集 (cluster)、單一世界地皮 | 不支援 |
| 評分、留言、經濟、Placeholder | 不支援 |

## 讀取的檔案

預設讀取 `plugins/PlotSquared/`（可在 `config.yml` 的 `data-folder` 修改）：

| 檔案 | 用途 |
| --- | --- |
| `config/worlds.yml` | 地皮世界設定（地皮大小、道路寬度、高度、方塊、生態域、家園位置） |
| `config/storage.yml` | 資料庫設定（SQLite 或 MySQL、資料表前綴 `prefix`） |
| `storage.db` | SQLite 資料庫 |

輕量版使用的資料表結構與 PlotSquared 完全相同（`plot`、`plot_settings`、`plot_helpers`、`plot_trusted`、`plot_denied`），
寫入的資料 PlotSquared 也能直接讀取。

## 從 PlotSquared 轉移

1. **先備份** `plugins/PlotSquared/` 與地皮世界資料夾（MySQL 請先匯出資料庫）。
2. 關閉伺服器，從 `plugins/` 移除 `PlotSquared` 的 jar（資料夾保留），放入 `PlotSquaredLite` 的 jar。
3. 如果地皮世界是用 **bukkit.yml** 或 **Multiverse** 指定生成器，要把 `PlotSquared` 改成 `PlotSquaredLite`：
   ```yaml
   # bukkit.yml
   worlds:
     plotworld:
       generator: PlotSquaredLite
   ```
   使用 Multiverse 的話，關服後把 `plugins/Multiverse-Core/worlds.yml` 裡該世界的 `generator` 欄位改成 `PlotSquaredLite`。
   沒有改的話，已經生成的區塊不受影響，但新生成的區塊會變成一般地形。啟動時插件會檢查並在後台提示。
   沒有被其他插件載入的地皮世界，輕量版會在啟動後自動用正確的生成器載入。
4. 啟動伺服器，後台應該會看到「已載入 N 個地皮世界、M 塊地皮」。
5. 權限節點與 PlotSquared 相同（例如 `plots.permpack.basic`、`plots.plot.5`、`plots.admin`），原本的權限組不用修改。

換回 PlotSquared 只要把 jar 換回去、把生成器名稱改回 `PlotSquared` 即可。

## 指令

主指令 `/plot`（別名 `/p`、`/plots`、`/ps`、`/p2`）。

| 指令 | 權限 | 說明 |
| --- | --- | --- |
| `/plot claim` | `plots.claim` | 認領腳下的地皮 |
| `/plot auto` | `plots.auto` | 自動認領一塊空地 |
| `/plot home [編號\|玩家] [編號]` | `plots.home`（看別人 `plots.visit.other`） | 回到地皮 |
| `/plot visit <玩家> [編號]` | `plots.visit.other` | 拜訪別人的地皮 |
| `/plot tp <x;z> [世界]` | `plots.tp` | 傳送到指定座標的地皮 |
| `/plot info` | `plots.info` | 地皮資訊 |
| `/plot list [玩家]` | `plots.list`（看別人 `plots.list.player`） | 列出地皮 |
| `/plot trust <玩家>` | `plots.trust` | 信任者，隨時可建築 |
| `/plot add <玩家>` | `plots.add` | 成員，擁有者在線時可建築 |
| `/plot remove <玩家>` | `plots.remove` | 從信任者、成員、禁止名單移除 |
| `/plot deny <玩家>` | `plots.deny` | 禁止進入（`*` 代表所有人） |
| `/plot undeny <玩家>` | `plots.undeny` | 解除禁止 |
| `/plot sethome [reset]` | `plots.set.home` | 設定家園位置 |
| `/plot clear` | `plots.clear` | 清除地皮（保留擁有權，需確認） |
| `/plot delete` | `plots.delete` | 刪除地皮（需確認） |
| `/plot setowner <玩家>` | `plots.admin.command.setowner` | 變更擁有者 |
| `/plot reload` | `plots.admin.command.reload` | 重新載入 config.yml 與 worlds.yml |

管理員繞過權限：`plots.admin.build.{road,unowned,other}`、`plots.admin.destroy.*`、`plots.admin.interact.*`、
`plots.admin.entry.denied`、`plots.admin.build.heightlimit`、`plots.admin.command.*`，全部包含在 `plots.admin` 裡。

## 建置

```bash
./gradlew -p Lite build
```

產生的 jar 在 `Lite/build/libs/`。推送到 GitHub 後，`Build PlotSquaredLite` workflow 也會自動建置並上傳 jar。

## 已知差異

- PvP：輕量版沒有 flags，改用 `config.yml` 的 `disable-pvp` 統一控制（預設禁止）。
- 刪除地皮時不會清掉 `plot_comments` 留言資料（PlotSquared 用地皮座標的雜湊值當索引，輕量版沒有留言功能）。
- 清除與刪除地皮會分散在多個 tick 執行，速度由 `clear.blocks-per-tick` 控制；伺服器在清除途中關閉的話，地形可能只清了一部分。

# YeePlot

Yeemo 伺服器的地皮插件，是 PlotSquared 的輕量版，只保留最基礎的地皮功能與權限控制，**直接讀寫 PlotSquared 原本的設定檔與資料庫**，
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
| WorldEdit／FastAsyncWorldEdit 權限保護 | 支援，規則與 PlotSquared 相同（見下方說明） |
| Axiom（AxiomPaper）權限保護 | 支援，玩家只能編輯自己有權限的地皮 |
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

YeePlot 使用的資料表結構與 PlotSquared 完全相同（`plot`、`plot_settings`、`plot_helpers`、`plot_trusted`、`plot_denied`），
寫入的資料 PlotSquared 也能直接讀取。

## 從 1.0.0（PlotSquaredLite）升級

1.1.0 起插件改名為 YeePlot：

- jar 換成 `YeePlot-x.x.x.jar`，刪除舊的 `PlotSquaredLite-1.0.0.jar`
- 第一次啟動會自動把 `plugins/PlotSquaredLite/` 的設定與未完成工作搬到 `plugins/YeePlot/`
- bukkit.yml／Multiverse 的生成器名稱要從 `PlotSquaredLite` 改成 `YeePlot`

## 從 PlotSquared 轉移

1. **先備份** `plugins/PlotSquared/` 與地皮世界資料夾（MySQL 請先匯出資料庫）。
2. 關閉伺服器，從 `plugins/` 移除 `PlotSquared` 的 jar（資料夾保留），放入 `YeePlot` 的 jar。
3. 如果地皮世界是用 **bukkit.yml** 或 **Multiverse** 指定生成器，要把 `PlotSquared` 改成 `YeePlot`：
   ```yaml
   # bukkit.yml
   worlds:
     plotworld:
       generator: YeePlot
   ```
   使用 Multiverse 的話，關服後把 `plugins/Multiverse-Core/worlds.yml` 裡該世界的 `generator` 欄位改成 `YeePlot`。
   沒有改的話，已經生成的區塊不受影響，但新生成的區塊會變成一般地形。啟動時插件會檢查並在後台提示。
   沒有被其他插件載入的地皮世界，YeePlot 會在啟動後自動用正確的生成器載入。
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
| `/plot list [玩家]`、`/plotlist [玩家]` | `plots.list`（看別人 `plots.list.player`） | 列出地皮，點擊地皮即可傳送 |
| `/plot trust <玩家>` | `plots.trust` | 信任者，隨時可建築 |
| `/plot add <玩家>` | `plots.add` | 成員，擁有者在線時可建築 |
| `/plot remove <玩家>` | `plots.remove` | 從信任者、成員、禁止名單移除 |
| `/plot deny <玩家>` | `plots.deny` | 禁止進入（`*` 代表所有人） |
| `/plot undeny <玩家>` | `plots.undeny` | 解除禁止 |
| `/plot sethome [reset]` | `plots.set.home` | 設定家園位置 |
| `/plot clear` | `plots.clear` | 清除地皮（保留擁有權，需確認） |
| `/plot delete` | `plots.delete` | 刪除地皮（需確認） |
| `/plot setowner <玩家>` | `plots.admin.command.setowner` | 變更擁有者 |
| `/plot fixroads [半徑]` | `plots.admin.command.fixroads` | 把周圍不屬於任何地皮的道路與圍牆恢復原樣（需確認） |
| `/plot reload` | `plots.admin.command.reload` | 重新載入 config.yml 與 worlds.yml |

管理員繞過權限：`plots.admin.build.{road,unowned,other}`、`plots.admin.destroy.*`、`plots.admin.interact.*`、
`plots.admin.entry.denied`、`plots.admin.build.heightlimit`、`plots.admin.command.*`，全部包含在 `plots.admin` 裡。

## WorldEdit 與 Axiom

WorldEdit 與 Axiom 都是直接寫入世界，不會觸發一般的方塊事件，所以 YeePlot 另外接上它們的介面。
三者都是選用的，沒安裝就不會載入對應的程式碼。

**WorldEdit／FastAsyncWorldEdit**（規則與 PlotSquared 相同）

- 只能編輯「目前站著」的那塊地皮；站在道路上時沿用上一次所在的地皮
- 合併地皮整個群組都可以編輯，包含被合併掉的道路
- 擁有者與信任者 (trust) 可以使用；成員 (add) 需要 `plots.worldedit.member`，且擁有者要在線
- 高度限制在 worlds.yml 的可建築範圍內；範圍外的方塊讀起來是空氣，無法複製別人的建築
- `plots.worldedit.bypass` 不受限制（預設給 OP）
- 地皮世界以外的世界不受影響
- FAWE：以 `PlotSquared` 為名稱註冊區域遮罩，玩家需要的權限仍是 `fawe.plotsquared`（FAWE 預設給所有人），
  `fawe.bypass.regions` 一樣可以繞過

**Axiom**

- 需要 AxiomPaper 提供的外部整合介面（新版本都有）。玩家可以在自己是擁有者、信任者，或擁有者在線時的成員的地皮上使用 Axiom，
  道路與別人的地皮會被擋下（管理員權限 `plots.admin.build.*` 照樣有效）
- Axiom 生成、移動、刪除實體同樣受地皮權限限制
- 展示實體（方塊、物品、文字展示）會依變換矩陣（位移、旋轉、縮放）計算實際外觀範圍，外觀超出可建築範圍時，
  生成會被取消、調整會被還原。物品展示的大小可以用 config 的 `axiom.item-display-size` 調整
- 限制：用 WorldEdit `//paste -e` 貼上的展示實體只檢查位置點，不檢查外觀範圍
- `axiomadmin.bypass_region_checks` 不受限制
- 舊版 AxiomPaper 沒有整合介面時，地皮世界中會整個禁止 Axiom 修改，啟動時後台會提示更新

## 合併地皮

YeePlot 不能建立新的合併，但 PlotSquared 已經合併好的地皮會完整沿用：

| 操作 | 行為 |
| --- | --- |
| 保護 | 合併群組內被吃掉的道路與十字路口算地皮的一部分，與 PlotSquared 相同 |
| trust／add／deny／remove、setowner | 套用到群組內每一塊 |
| home、sethome | 以群組的基準地皮（z 最小，其次 x 最小）為準 |
| info | 顯示合併方向 |
| clear | 整個群組一起清除，中間的道路鋪成地皮地板，保持合併 |
| delete | 整個群組一起刪除，中間道路、外圍圍牆、合併缺口兩端全部恢復原樣，道路上的實體一併清除（無法只刪其中一塊） |
| WorldEdit／Axiom | 整個群組都可以編輯 |
| 地皮數量上限 | 與 PlotSquared 相同，合併的每一塊分別計算 |

## 修復原版留下的道路殘留

原版 PlotSquared 刪除合併地皮時，常見道路上殘留方塊。從原始碼看，可能的原因有：

- 重建道路時，只清到 worlds.yml 的 `max_gen_height`，更高的方塊會留下（從舊版本升級的世界常常設成 255）
- 先把合併狀態解除，再清除地皮，所以清除實體的範圍只有地皮內部，道路上的盔甲架、展示框、畫會留下
- 地形修改透過非同步佇列執行，伺服器在途中關閉或區塊處理失敗時，就只清了一部分

YeePlot 的刪除流程已經避開這些問題。已經留下的殘留，管理員可以站到附近執行 `/plot fixroads [半徑]`：

- 只處理「不屬於任何地皮」的道路與圍牆；地皮內部（不論是否認領）與合併道路完全不動
- 合併地皮外圍接起來的圍牆會保留，圍牆頂端依旁邊地皮是否認領放對應的方塊
- 只移除道路上的展示框、畫、盔甲架與展示實體，不會誤殺生物或礦車
- 有道路模板 (road schematic) 的世界，修復後道路會變回經典樣式

## 建置

```bash
./gradlew -p YeePlot build
```

產生的 jar 在 `YeePlot/build/libs/`。推送到 GitHub 後，`Build YeePlot` workflow 也會自動建置並上傳 jar。

## 已知差異

- PvP：YeePlot 沒有 flags，改用 `config.yml` 的 `disable-pvp` 統一控制（預設禁止）。
- 刪除地皮時不會清掉 `plot_comments` 留言資料（PlotSquared 用地皮座標的雜湊值當索引，YeePlot 沒有留言功能）。
- 清除與刪除地皮會分散在多個 tick 執行，速度由 `clear.blocks-per-tick` 控制。未完成的工作記在 `plugins/YeePlot/pending-regen.yml`，
  伺服器途中關閉的話，下次啟動會自動接著做完。
- 每一欄都從世界最低點清到最高點，不受 worlds.yml 的 `max_gen_height` 限制。

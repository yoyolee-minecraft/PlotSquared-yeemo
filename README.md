# YeePlot

Yeemo 伺服器的地皮插件。它是 PlotSquared 的輕量版，只保留地皮生成、認領、權限保護等基礎功能，
並且**直接讀寫 PlotSquared 原本的設定檔與資料庫**：可以直接取代 PlotSquared，也可以隨時換回去。

- 需要 Paper 1.21 以上、Java 21
- 選用整合：WorldEdit 或 FastAsyncWorldEdit（FAWE）、AxiomPaper；沒安裝的話，對應的程式碼不會載入
- 不打包任何第三方函式庫（SQLite 與 MySQL 驅動由伺服器本身提供），jar 約 140 KB

下載：[GitHub Releases](https://github.com/yoyolee-minecraft/PlotSquared-yeemo/releases)（目前都是預覽版，上線前請先在測試服用備份資料測試）

> 這個 repo 是從 [PlotSquared](https://github.com/IntellectualSites/PlotSquared) 分支出來的。
> YeePlot 的程式碼在 [`YeePlot/`](YeePlot) 資料夾；`Core/`、`Bukkit/` 是原版 PlotSquared 的程式碼，保留作為參考與資料格式的依據。

---

## 目錄

- [安裝](#安裝)
- [指令](#指令)
- [權限](#權限)
- [設定檔](#設定檔)
- [功能說明](#功能說明)
- [與 PlotSquared 的資料相容](#與-plotsquared-的資料相容)
- [不支援的功能與已知差異](#不支援的功能與已知差異)
- [建置與發佈](#建置與發佈)
- [授權](#授權)

---

## 安裝

### 從 PlotSquared 轉移

1. **先備份** `plugins/PlotSquared/` 與地皮世界資料夾（使用 MySQL 的話先匯出資料庫）
2. 關閉伺服器，從 `plugins/` 移除 PlotSquared 的 jar（**資料夾要保留**），放入 `YeePlot-x.x.x.jar`
3. 地皮世界如果是由 bukkit.yml 或 Multiverse 指定生成器，把生成器名稱從 `PlotSquared` 改成 `YeePlot`：
   ```yaml
   # bukkit.yml
   worlds:
     plotworld:
       generator: YeePlot
   ```
   Multiverse 則是在關服後，把 `plugins/Multiverse-Core/worlds.yml` 裡該世界的 `generator` 改成 `YeePlot`。

   沒改的話，已經生成的區塊不受影響，但新生成的區塊會變成一般地形，啟動時後台會提示。
   沒有被其他插件載入的地皮世界，YeePlot 會在啟動後自動用正確的生成器載入。
4. 啟動伺服器，後台應該出現「已載入 N 個地皮世界、M 塊地皮」，M 要跟原本的地皮數一致
5. 權限節點與 PlotSquared 相同，原本的權限組不用修改

**換回 PlotSquared**：把 jar 換回去，生成器名稱改回 `PlotSquared` 即可。

### 從 PlotSquaredLite 1.0.0 升級

1.1.0 起插件改名為 YeePlot：

- 刪除 `PlotSquaredLite-1.0.0.jar`，放入 `YeePlot-x.x.x.jar`
- 第一次啟動時，會自動把 `plugins/PlotSquaredLite/` 的設定與未完成的地形工作搬到 `plugins/YeePlot/`，確認無誤後可以刪除舊資料夾
- 生成器名稱從 `PlotSquaredLite` 改成 `YeePlot`

### 升級 YeePlot

直接替換 jar。插件不會覆蓋已經存在的 `config.yml`，但會在啟動與 `/plot reload` 時自動補上新版本的設定，
詳見[設定檔自動更新](#設定檔自動更新)。

---

## 指令

主指令 `/plot`，別名 `/p`、`/plots`、`/ps`、`/p2`、`/plotsquared`、`/2`。需要確認的指令，會提示在 20 秒內輸入 `/plot confirm`。

### 一般玩家

| 指令 | 權限 | 說明 |
| --- | --- | --- |
| `/plot claim` | `plots.claim` | 認領腳下的地皮 |
| `/plot auto` | `plots.auto` | 自動認領離中心最近的空地 |
| `/plot home [編號\|玩家] [編號\|別名]` | `plots.home`；看別人的要 `plots.visit.other` | 傳送到地皮 |
| `/plot visit <玩家\|別名> [編號\|別名]` | `plots.visit.other` | 拜訪別人的地皮；例如 `/plot visit Steve 2`、`/plot visit Steve 城堡`、`/plot visit 城堡` |
| `/plot list [玩家]`、`/plotlist [玩家]` | `plots.list`；看別人的要 `plots.list.player` | 列出地皮，**點擊即可傳送** |
| `/plot info` | `plots.info` | 別名、擁有者、名單、合併方向、時間天氣、展示實體數量 |
| `/plot trust <玩家>` | `plots.trust` | 加入信任者（隨時可以建築） |
| `/plot add <玩家>` | `plots.add` | 加入成員（擁有者在線時才可以建築） |
| `/plot remove <玩家>` | `plots.remove` | 從信任者、成員、禁止名單移除 |
| `/plot deny <玩家>` | `plots.deny` | 禁止進入，人在裡面會被送回出生點 |
| `/plot undeny <玩家>` | `plots.undeny` | 解除禁止 |
| `/plot sethome [reset]` | `plots.set.home` | 設定或重設傳送位置 |
| `/plot alias set <名稱>`、`/plot alias remove` | `plots.alias.set`、`plots.alias.remove` | 設定或移除地皮別名 |
| `/plot time <時間\|reset>` | `plots.set.flag.time` | 設定地皮時間：0~24000，或 day、noon、night、midnight |
| `/plot weather <clear\|rain\|reset>` | `plots.set.flag.weather` | 設定地皮天氣 |
| `/plot flag set\|remove <time\|weather> [值]` | 同上 | 相容 PlotSquared 的寫法 |
| `/plot merge [north\|east\|south\|west]` | `plots.merge` | 與相鄰的自己的地皮合併（需確認），不填方向就用面向的方向 |
| `/plot clear` | `plots.clear` | 清除地皮內容，保留擁有權（需確認） |
| `/plot delete` | `plots.delete` | 刪除地皮並還原地形（需確認） |

名單指令可以用 `*` 代表所有人，需要 `plots.trust.everyone`、`plots.add.everyone`、`plots.deny.everyone`。

### 管理員

| 指令 | 權限 | 說明 |
| --- | --- | --- |
| `/plot tp <x;z> [世界]` | `plots.tp` | 傳送到指定座標的地皮 |
| `/plot setowner <玩家>` | `plots.admin.command.setowner` | 變更擁有者（整個合併群組） |
| `/plot fixroads [半徑]` | `plots.admin.command.fixroads` | 把周圍不屬於任何地皮的道路與圍牆恢復原樣（需確認） |
| `/plot reload` | `plots.admin.command.reload` | 重新載入 config.yml 與 worlds.yml |

管理員也可以對別人的地皮執行 trust、deny、sethome、alias、time、weather、merge、clear、delete，權限見下方。

---

## 權限

### 權限包（與 PlotSquared 相同）

| 權限 | 內容 |
| --- | --- |
| `plots.permpack.basic` | 一般玩家的所有基本指令，含 `plots.merge` 與 `plots.merge.4` |
| `plots.permpack.basicflags` | `plots.set.flag.time`、`plots.set.flag.weather` |
| `plots.admin` | 所有管理員權限（預設給 OP） |

### 數量上限

| 權限 | 說明 |
| --- | --- |
| `plots.plot.<數字>` | 可以擁有幾塊地皮（合併的每一塊分別計算）；`plots.plot.*` 不限制。**沒有這個權限的話上限是 0** |
| `plots.merge.<數字>` | 合併群組最多幾塊地皮；沒有數字權限時用 config 的 `merge.default-max-plots` |

### 管理員繞過（都包含在 `plots.admin` 裡）

| 權限 | 說明 |
| --- | --- |
| `plots.admin.build.road`／`.unowned`／`.other` | 在道路、未認領地皮、別人的地皮放置方塊 |
| `plots.admin.destroy.road`／`.unowned`／`.other` | 破壞方塊 |
| `plots.admin.interact.road`／`.unowned`／`.other` | 開門、開箱子等互動 |
| `plots.admin.build.heightlimit` | 超出可建築高度 |
| `plots.admin.entry.denied` | 進入被禁止的地皮 |
| `plots.admin.pvp` | 在地皮世界 PvP |
| `plots.admin.displaylimit` | 不受展示實體數量上限限制 |
| `plots.admin.command.*` | 對別人的地皮執行指令（trust、deny、remove、undeny、sethome、merge、clear、delete、setowner、fixroads、reload） |
| `plots.set.flag.other` | 設定別人地皮的時間與天氣 |
| `plots.admin.alias.set`／`.remove` | 設定或移除別人地皮的別名 |

### WorldEdit 相關

| 權限 | 預設 | 說明 |
| --- | --- | --- |
| `plots.worldedit.bypass` | OP | WorldEdit 不受地皮範圍限制 |
| `plots.worldedit.member` | 無 | 成員也可以在地皮上使用 WorldEdit |
| `plots.worldedit.displays` | **無（包含 OP）** | 在地皮世界用 WorldEdit 建立展示實體（例如 `//paste -e`） |
| `fawe.plotsquared` | 所有人 | FAWE 的區域權限（由 FAWE 定義，YeePlot 沿用） |

> 注意：OP 預設擁有大部分繞過權限，測試保護功能時請用沒有 OP 的帳號。

---

## 設定檔

`plugins/YeePlot/config.yml`：

| 設定 | 預設 | 說明 |
| --- | --- | --- |
| `data-folder` | `plugins/PlotSquared` | PlotSquared 的資料夾，相對於伺服器根目錄 |
| `limits.global` | `false` | 地皮上限是否跨世界計算 |
| `limits.max-plots` | `127` | 檢查 `plots.plot.<數字>` 的最大數字 |
| `clear.blocks-per-tick` | `40000` | 清除、刪除、合併、修復道路時，每 tick 最多改動的方塊數 |
| `fix-roads.max-radius` | `128` | `/plot fixroads` 允許的最大半徑 |
| `displays.per-plot` | `100` | 每塊地皮的展示實體上限，合併依塊數累加；`0` 代表不限制 |
| `merge.default-max-plots` | `4` | 沒有 `plots.merge.<數字>` 時的合併上限 |
| `axiom.item-display-size` | `1.0` | 計算 Axiom 物品展示外觀範圍時的大小（格）；資源包有大型模型可以調高（最大約 3） |
| `teleport-on-claim` | `true` | 認領後自動傳送 |
| `disable-pvp` | `true` | 地皮世界禁止玩家互相攻擊 |
| `messages.*` | | 所有訊息，支援 `&` 色碼；前綴預設是 `&8[&6Yeemo小幫手&8] &7` |

修改後執行 `/plot reload` 即可生效；`disable-pvp` 與 `axiom.item-display-size` 需要重啟伺服器。

地皮世界本身的設定（地皮大小、道路寬度、高度、方塊、生態域、家園位置）沿用 PlotSquared 的 `config/worlds.yml`。

### 設定檔自動更新

從舊版升級時，YeePlot 會在啟動與 `/plot reload` 時：

- 補上缺少的新設定（連同註解）
- `messages.info`、`messages.help` 缺少新版欄位時換成新版樣板（**這兩項自訂過的內容會被覆蓋**）
- 其他已經存在的設定一律保留

後台會列出更新了哪些項目。

---

## 功能說明

### 地皮世界與保護

- 地形生成與座標計算和 PlotSquared 的經典地皮世界完全相同，既有地皮的位置不會改變
- 擁有者、信任者可以建築；成員只有擁有者在線時才可以；道路、未認領地皮、別人的地皮都受保護
- 防止跨越地皮邊界：液體流動、活塞推動、火焰蔓延、樹木生長、爆炸
- 終界使者、凋零、劫毀獸、蠹魚無法改變地皮世界的方塊
- 被禁止的玩家無法走進或傳送進地皮
- 可建築高度依 worlds.yml 的 `world.min_height`、`world.max_height`

### 地皮列表點擊傳送

`/plot list` 與 `/plotlist` 列出的每塊地皮都可以點擊，滑鼠移上去會顯示座標。點自己的地皮會執行 `/plot home 編號`，
點別人的地皮會執行 `/plot visit 玩家 編號`，所以傳送權限與禁止進入照常檢查。

### 地皮別名

- `/plot alias set <名稱>` 為地皮取名，之後用 `/plot visit <名稱>` 或 `/plot visit <玩家> <名稱>` 就能直接傳送
- `/plot list` 有別名的地皮會顯示別名、沒有的顯示座標（滑鼠移上去仍會顯示座標）；`/plot info` 也會顯示別名
- 規則與 PlotSquared 相同：一個單字、最多 49 個字、不能是純數字、同一個世界內不能重複（不分大小寫）、不能跟玩家名稱相同
- 另外禁止 `&` 與色碼字元，避免在訊息中產生格式
- 別名套用到整個合併群組；合併時沿用執行指令那一邊的別名，那一邊沒有的話用另一邊的
- 存在 PlotSquared 的 `plot_settings.alias` 欄位，原版設定過的別名直接沿用

### 時間與天氣

- 只改變**站在地皮上的玩家**看到的時間與天氣，離開地皮就恢復世界原本的狀態，不影響其他玩家
- 設定存成 PlotSquared 的 `time`、`weather` flag：原版設定過的值會直接生效，換回原版也還在
- 設定會套用到整個合併群組

### 合併地皮

PlotSquared 已經合併好的地皮會完整沿用。新的合併使用 `/plot merge`，規則如下：

- 只能合併**同一個擁有者、相鄰**的地皮；管理員（`plots.admin.command.merge`）可以替別人合併，但兩邊仍須是同一個擁有者
- 合併後必須是**完整的長方形**，不能做 L 形
- 信任者、成員、禁止名單取聯集；時間、天氣與別名沿用執行指令那一邊的設定
- 只把這次新併入的道路鋪成地皮地板，外圍圍牆接起來；**已經合併過的道路上的建築不會被動到**
- **不能拆分**，要拆就整組刪除

既有的與新的合併群組，都依下表處理：

| 操作 | 行為 |
| --- | --- |
| 保護 | 群組內被合併掉的道路與十字路口算地皮的一部分 |
| 名單、setowner、時間天氣、別名 | 套用到群組內每一塊 |
| home、sethome | 以群組的基準地皮（z 最小，其次 x 最小）為準 |
| clear | 整個群組一起清除，中間的道路鋪成地皮地板，保持合併 |
| delete | 整個群組一起刪除；中間道路、外圍圍牆、合併缺口兩端全部還原，道路上的實體一併清除 |
| WorldEdit、Axiom | 整個群組都可以編輯 |

### 清除與刪除

- 工作分散在多個 tick 執行，速度由 `clear.blocks-per-tick` 控制
- 每一欄都從世界最低點清到最高點，不受 worlds.yml 的 `max_gen_height` 限制
- 未完成的工作記在 `plugins/YeePlot/pending-regen.yml`，伺服器途中關閉的話，下次啟動會自動接著做完
- 刪除進行中，這些地皮不能被重新認領

### 修復原版留下的道路殘留

原版 PlotSquared 刪除合併地皮後，道路上常留下方塊。從原始碼看，可能的原因有：

- 重建道路時只清到 worlds.yml 的 `max_gen_height`（從舊版本升級的世界常常是 255），更高的方塊會留下
- 先解除合併再清除，清除實體的範圍只剩地皮內部，道路上的盔甲架、展示框、畫會留下
- 地形修改在背景執行，伺服器途中關閉時只清了一部分

YeePlot 的刪除流程已經避開這些問題。已經留下的殘留，管理員可以站到附近執行 `/plot fixroads [半徑]`：

- 只處理不屬於任何地皮的道路與圍牆；地皮內部（不論是否認領）與合併道路完全不動
- 合併群組外圍接起來的圍牆會保留，圍牆頂端依旁邊地皮是否認領放對應的方塊
- 只移除道路上的展示框、畫、盔甲架、展示實體，不會誤殺生物或礦車
- 有道路模板（road schematic）的世界，修復後的道路會變回經典樣式

### WorldEdit 與 FAWE

WorldEdit 直接寫入世界，不會觸發一般的方塊事件，所以另外接上它的介面。規則與 PlotSquared 相同：

- 只能編輯**目前站著**的那塊地皮（合併群組整個算進去）；站在道路上時沿用上一次所在的地皮
- 擁有者與信任者可以使用；成員需要 `plots.worldedit.member`，而且擁有者要在線
- 高度限制在可建築範圍內；範圍外的方塊讀起來是空氣，無法複製別人的建築
- 在地皮世界建立實體時（`//paste -e`、`//stack` 等）**一律不建立展示實體**，避免透過變換矩陣畫到地皮外
- 地皮世界以外的世界不受影響

FAWE 另外處理：

- 範圍限制透過 FAWE 的區域遮罩機制，名稱沿用 `PlotSquared`，所以玩家現有的 `fawe.plotsquared` 權限照樣有效
- FAWE 會擋掉不在 `extent.allowed-plugins` 白名單裡的第三方 extent。YeePlot 啟動時會自動把自己加入白名單
  （只改記憶體中的設定，不寫入 FAWE 的設定檔），不需要手動設定

### Axiom

需要 AxiomPaper 提供的外部整合介面（新版本都有）：

- 玩家只能在自己有建築權的地方使用 Axiom，道路與別人的地皮會被擋下
- Axiom 生成、移動、刪除實體同樣受地皮權限限制
- 展示實體依變換矩陣（位移、旋轉、縮放）計算實際外觀範圍；外觀超出可建築範圍時，生成會被取消、調整會被還原
- `axiomadmin.bypass_region_checks` 不受限制
- 舊版 AxiomPaper 沒有整合介面時，地皮世界中會整個禁止 Axiom 修改，啟動時後台會提示更新

### 展示實體數量上限

- 每塊地皮最多 `displays.per-plot` 個展示實體（方塊、物品、文字展示），合併群組依塊數累加
- 目前限制 Axiom 生成；WorldEdit 本來就不會建立展示實體；其他插件或 `/summon` 產生的不在限制範圍內
- 只計算已載入區塊裡的實體
- `/plot info` 會顯示目前數量與上限

---

## 與 PlotSquared 的資料相容

| 檔案 | 用途 |
| --- | --- |
| `plugins/PlotSquared/config/worlds.yml` | 地皮世界設定 |
| `plugins/PlotSquared/config/storage.yml` | 資料庫設定（SQLite 或 MySQL、資料表前綴） |
| `plugins/PlotSquared/storage.db` | SQLite 資料庫 |

- 使用的資料表與 PlotSquared 相同：`plot`、`plot_settings`、`plot_helpers`、`plot_trusted`、`plot_denied`、`plot_flags`
- 合併狀態、家園位置、別名、time 與 weather flag 使用原版的格式
- 其他 flag（pvp、greeting 等）YeePlot 不使用，但會原樣保留在資料庫，換回 PlotSquared 時仍然有效
- 刪除地皮時會一併刪除名單、設定、評分與 flag

---

## 不支援的功能與已知差異

**不支援**

- 拆分合併、L 形合併、合併別人的地皮
- time、weather 以外的 flag
- 道路模板與地皮模板（新區塊只會生成經典地形）
- 部分地皮區域（`generator.type: 2`）、地皮叢集（cluster）、一塊地皮一個世界
- 評分、留言、經濟、Placeholder

**與 PlotSquared 的差異**

- PvP 由 config 的 `disable-pvp` 統一控制（預設禁止），沒有 pvp flag
- 在別人的地皮上，所有可互動方塊與村民交易都會被擋，沒有 flag 可以開放
- 刪除地皮時不會清掉 `plot_comments` 留言資料（YeePlot 沒有留言功能）

---

## 建置與發佈

```bash
./gradlew -p YeePlot build
```

產生的 jar 在 `YeePlot/build/libs/`。

推送到 GitHub 後，`Build YeePlot` workflow 會自動建置並上傳 jar。當 `YeePlot/build.gradle.kts` 的版本號還沒有對應的 Release 時，
會自動建立 `yeeplot-v<版本>` 預覽版 Release，Release 說明取自 `YeePlot/RELEASE_NOTES.md`。
發新版只要修改版本號並推送。

---

## 授權

YeePlot 的地皮座標計算、地形生成規則與資料表結構沿用 PlotSquared，與 PlotSquared 相同採用
[GNU General Public License v3.0](LICENSE) 授權。PlotSquared 的著作權屬於 IntellectualSites 與其貢獻者。

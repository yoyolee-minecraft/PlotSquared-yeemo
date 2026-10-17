Yeemo 伺服器的地皮插件（PlotSquared 的輕量版），直接讀寫 PlotSquared 原本的 `worlds.yml`、`storage.yml` 與資料庫，可以直接取代，也可以隨時換回去。

> ⚠️ 這是預覽版本，尚未在正式伺服器上長時間運作。請先在測試服使用備份資料測試，再上線。

## 1.5.1 更新內容
- 多行訊息（`/plot list`、`/plot info`、`/plot help`、確認提示）只有第一行顯示 `[Yeemo小幫手]`，之後的行用空白對齊第一行的內容
- 對齊寬度依原版字型自動計算；資源包改變字型而對不齊時，可以在 `messages.prefix-width` 填像素寬度

## 1.5.0 更新內容
- `/plot list` 分頁，每頁 10 塊（`list.page-size` 可以改），最下面有可以點擊的「上一頁」「下一頁」；也可以用 `/plot list [玩家] <頁數>`
- 輸入玩家名稱的指令都能按 Tab 補齊，包含離線的地皮擁有者；`remove`、`undeny` 補上腳下地皮名單裡的玩家，`visit` 也會補上地皮別名
- `/plot info` 的信任者、成員、禁止名單，擁有者會在名字後面看到 [X]，點擊即可移除
- `/plot info` 最下面新增可以點擊的「其他領地」，列出這塊地皮擁有者的所有地皮
- `/plot list <自己的名字>` 不再需要 `plots.list.player`
- 舊版 `config.yml` 會自動補上新訊息與設定，`messages.help` 會更新成新版

## 1.4.0 更新內容
- 支援 PlotSquared 的 AUGMENTED 地皮世界（在原版等原本地形上劃出地皮格線，worlds.yml 有 `generator.type: AUGMENTED`）。
  之前這種世界會被整個略過，地皮讀不到
- 這種世界的地形不屬於 YeePlot，任何操作都不會修改：刪除只取消認領、清除與修復道路停用、合併只合併資料、認領不放圍牆半磚
- 世界維持原本的生成器（Multiverse 裡的 generator 不要改）

## 1.3.2 修正
- 修正 `/plot visit <玩家> <別名>`（以及 `/plot home <玩家> <別名>`）沒有讀取別名，總是傳送到第一塊地皮；找不到別名時會提示，不會傳送

## 1.3.1 更新內容
- `/plot list` 有別名的地皮優先顯示別名，沒有別名才顯示座標；滑鼠移上去仍會顯示座標
- 舊版 `config.yml` 的 `messages.list-entry` 會自動更新成新版樣板

## 1.3.0 更新內容
- 新增 `/plot alias set <名稱>`、`/plot alias remove`，規則與 PlotSquared 相同；`/plot visit <別名>` 可以直接傳送
- 合併時沿用別名
- 移除用不到的原版 PlotSquared workflow

## 1.2.1 修正
- 修正 FAWE 仍會貼上展示實體（`//paste -e`、`//stack`）：FAWE 會擋掉不在白名單的第三方 extent，現在啟動時自動加入白名單
- WorldEdit 建立展示實體改用獨立權限 `plots.worldedit.displays`（預設沒有人有，包含 OP）
- 舊版的 `config.yml` 會自動補上新設定（例如 `displays.per-plot`），`/plot info` 的樣板也會更新成顯示展示實體數量

## 1.2.0 更新內容
- 新增 `/plot time`、`/plot weather`（也相容 `/plot flag set time|weather`），沿用 PlotSquared 的 time、weather flag
- 新增有限制的 `/plot merge`：只能合併自己相鄰的地皮、合併後必須是長方形、不能拆分
- 每塊地皮的展示實體數量上限（預設 100，合併依塊數累加），`/plot info` 顯示目前數量
- WorldEdit／FAWE 在地皮世界貼上時不貼展示實體

## 1.1.0 更新內容
- 插件改名為 **YeePlot**，訊息前綴改為「[Yeemo小幫手]」
- Axiom 展示實體不能再透過位移或縮放畫到地皮外（超出時取消生成、還原調整）
- `/plot list` 與新指令 `/plotlist` 列出的地皮可以點擊傳送
- 從 1.0.0 升級：第一次啟動會自動搬移 `plugins/PlotSquaredLite/` 的設定；生成器名稱改成 `YeePlot`

## 需求
- Paper 1.21 以上、Java 21
- 選用：WorldEdit 或 FastAsyncWorldEdit、AxiomPaper

## 功能
- 地皮世界生成（與 PlotSquared 經典地形相同的座標計算）
- 認領、自動認領、家園、拜訪、資訊、列表
- trust／add／remove／deny／undeny、sethome、clear、delete、setowner
- 建築、破壞、互動保護；液體、活塞、爆炸不跨越地皮邊界；禁止進入
- WorldEdit／FAWE 權限保護（規則與 PlotSquared 相同）
- Axiom 權限保護（只能編輯自己有權限的地皮）
- 完整沿用既有的合併地皮；刪除合併地皮時道路、外圍圍牆、合併缺口完整還原
- `/plot fixroads`：修復原版 PlotSquared 留在道路上的殘留方塊
- 清除／刪除工作在伺服器重啟後自動接續

## 安裝與轉移
1. 備份 `plugins/PlotSquared/` 與地皮世界
2. 移除 PlotSquared 的 jar（資料夾保留），放入 `YeePlot-x.x.x.jar`
3. bukkit.yml 或 Multiverse 中的地皮世界生成器，從 `PlotSquared` 改成 `YeePlot`
4. 啟動伺服器，後台應出現「已載入 N 個地皮世界、M 塊地皮」

權限節點與 PlotSquared 相同。完整說明見 [README](https://github.com/yoyolee-minecraft/PlotSquared-yeemo#readme)。

## 不支援
拆分合併、L 形合併、time 與 weather 以外的 flags（資料庫中的 flags 會原樣保留）、道路與地皮模板、部分地皮區域（generator.type PARTIAL）、叢集、評分、留言、經濟。

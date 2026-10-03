Yeemo 伺服器的地皮插件（PlotSquared 的輕量版），直接讀寫 PlotSquared 原本的 `worlds.yml`、`storage.yml` 與資料庫，可以直接取代，也可以隨時換回去。

> ⚠️ 這是預覽版本，尚未在正式伺服器上長時間運作。請先在測試服使用備份資料測試，再上線。

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
拆分合併、L 形合併、time 與 weather 以外的 flags（資料庫中的 flags 會原樣保留）、道路與地皮模板、部分地皮區域（generator.type 2）、叢集、評分、留言、經濟。

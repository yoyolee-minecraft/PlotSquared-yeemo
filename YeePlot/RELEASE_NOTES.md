Yeemo 伺服器的地皮插件（PlotSquared 的輕量版），直接讀寫 PlotSquared 原本的 `worlds.yml`、`storage.yml` 與資料庫，可以直接取代，也可以隨時換回去。

> ⚠️ 這是預覽版本，尚未在正式伺服器上長時間運作。請先在測試服使用備份資料測試，再上線。

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

權限節點與 PlotSquared 相同。完整說明見 [YeePlot/README.md](https://github.com/yoyolee-minecraft/PlotSquared-yeemo/blob/main/YeePlot/README.md)。

## 不支援
新的合併與拆分、flags（資料庫中的 flags 會原樣保留）、道路與地皮模板、部分地皮區域（generator.type 2）、叢集、評分、留言、經濟。

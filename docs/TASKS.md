# 開發里程碑與任務清單

估算以一位熟悉 Kotlin/Android 的工程師為基準，只表示相對工作量；Play 審查、外部地圖服務與 OEM 相容性等待時間不包含在內。

## Gate 0：產品識別與外部資源（0.5–1 天）

- [x] 決定 App 顯示名稱：BloomWalk GPS（繁中：花路漫步 GPS）
- [x] 決定不可隨意更改的 `applicationId`：`com.bloss0m.bloomwalk`
- [x] 決定最低支援語言：繁中、英文
- [x] 選定 MapLibre + OpenFreeMap，不需要 Billing 或 API key
- [x] 確認 OpenFreeMap 樣式 URL 與 OpenStreetMap attribution
- [ ] 決定地點搜尋供應商、使用政策與流量限制
- [ ] 準備 API 26、34、36 測試裝置；至少一台使用 Google APIs image

出口條件：package name、地圖供應策略、測試矩陣可用。

## Milestone 1：核心可行性 Spike（1–2 天）

這是第一個實作批次，先證明產品心臟。

- [x] 建立 Gradle Kotlin DSL Android 專案、version catalog、Compose、min/target/compile SDK
- [x] 加入 debug-only 固定座標輸入畫面（預設 Taipei 101）
- [x] 建立 `Coordinate`、`MockPayload`、validator、payload factory
- [x] 建立 `MockLocationEngine` contract
- [x] 實作 API 26–30 與 API 31+ 的 `FrameworkMockEngine`
- [x] 實作 `FusedMockEngine`
- [x] 實作 coordinator 的 all-start-or-rollback、停止失敗重試與冪等 cleanup
- [x] 實作最小 foreground service、ongoing notification、Stop action
- [x] 實作 Developer Options 入口與 `SecurityException` 錯誤狀態
- [ ] 在 API 26、34、36 驗證 Start/Stop
- [ ] 用另一個 LocationManager client 與 FLP client 驗證座標
- [x] 連續 Start/Stop 20 次並檢查殘留狀態
- [x] 維持 GPS + FLP；未有裝置測試證據前不加入 `NETWORK_PROVIDER`

驗證進度（2026-08-23）：8 個 unit tests、`assembleDebug`、`lintDebug` 均通過。在 Sony XQ-BC72（Android 13 / API 33）完成實機核心 Gate：GPS 與 FLP 均持續輸出台北 101 mock 座標、退到背景後服務維持、通知列 Stop 可清除服務與 mock provider，且 20/20 次 Start/Stop 無服務、通知或 provider 殘留，期間無 crash、ANR 或 `SecurityException`。API 26/34/36 矩陣與獨立 LocationManager/FLP client 驗證仍待執行。

出口條件：固定座標可在背景持續，通知 Stop 後所有引擎清理完成。若此 Gate 不通過，不開始完整地圖 UI。

## Milestone 2：地圖垂直切片（2–3 天）

- [x] 安裝 MapLibre Compose 並接入 OpenFreeMap
- [x] 移除 Google Maps、Secrets Plugin 與 API key 設定
- [x] 建立 `MapScreen`、`MapViewModel`、immutable `MapUiState`
- [x] 顯示 OpenFreeMap 向量地圖與中央準星
- [x] camera idle 時同步選定座標
- [x] 顯示座標與 loading/error/retry
- [x] 明亮／深色地圖樣式切換
- [x] 將固定座標 Start/Stop 換成地圖選定座標
- [x] Active 中更換選點時要求明確「套用新位置」
- [x] Activity 重建與旋轉後恢復 camera/selection，並重新觀察 service state

實作進度（2026-08-23）：地圖垂直切片、Active 明確套用與完整 camera state 已完成，並改用不需要帳號或 API key 的 MapLibre + OpenFreeMap。在 Sony XQ-BC72（Android 13 / API 33）完成實機 Gate：明亮／深色圖磚載入、中央準星拖曳選點、旋轉後 camera/selection 保留、橫向畫面捲動、Start、Active 中明確套用新位置、GPS/FLP 與通知座標同步，以及 Stop 後清除服務、通知與 mock provider；期間無 crash 或 ANR。跨 App 客戶端驗證與 API 26/34/36 矩陣仍待執行。

UX／效能更新（2026-08-24）：改為 edge-to-edge 滿版地圖與安全區浮動控制卡片，移除地圖外層捲動及固定高度造成的手勢競爭；縮小 MapLibre 重組輸入、僅在 camera idle 提交座標、忽略無變化的 camera state，並在樣式載入期間鎖定切換。Sony 實機直向／橫向、20 次連續拖曳、明暗切換及完整 Start → Apply → Stop 回歸均通過，無 crash 或 ANR。

出口條件：拖地圖選點 → Start → 跨 App 驗證 → 通知 Stop 的完整流程通過。

## Milestone 3：設定與權限 UX（1–2 天）

- [x] 首次 Setup／權限引導：定位與通知權限、開發人員選項入口與可恢復錯誤
- [x] Current-location shortcut 與 coarse/fine permission 流程
- [x] API 33+ notification permission 說明
- [x] FGS 啟動例外與 rollback
- [x] Google Play services unavailable 狀態分類與 GPS+FLP rollback
- [x] 地圖／搜尋服務的網路與供應商錯誤分類
- [x] DataStore：map type、interval、accuracy、show coordinates、last coordinate
- [x] 通知內容跟隨座標更新；設定資料層獨立保存
- [x] 繁中／英文 string resources（174 個鍵 parity）

出口條件：首次使用者能自行完成設定；拒絕任一可拒絕權限不造成 crash 或半 Active。

## Milestone 4：地點搜尋（1–2 天）

- [x] 建立可注入 `PlaceSearchRepository`
- [x] 整合 Nominatim OSM 相容搜尋服務
- [x] 管理 debounce、每秒一請求限制與取消過期結果
- [x] 只要求顯示名稱與座標所需欄位
- [x] 搜尋建議 UI、鍵盤操作、loading/empty/error
- [x] 點結果後 animate camera 並更新 selection
- [x] 搜尋清空、loading/empty/error 的 focused Compose tests

出口條件：可搜尋 Tokyo Station 並成功開始該座標的靜態模擬。

## Milestone 5：收藏與最近位置（1–2 天）

- [x] Room database、entities、DAO、repository
- [x] 收藏新增、命名、重新命名、刪除、選取
- [x] 成功 Active 後才 upsert recent
- [x] 最近位置排序、去重、最多 50 筆
- [x] 刪除單一收藏確認流程
- [x] 清除全部收藏／歷史確認流程
- [x] 收藏重啟 persistence 測試
- [x] 最近位置第 51 筆裁切測試

實作進度（2026-08-24）：喜愛地點與最近位置以 Room 完成本機持久化；最近位置只在靜態服務進入 Active 後寫入，相同六位小數座標會刷新時間，並在同一 transaction 保留最新 50 筆。本次變更已通過 JVM 測試與 instrumentation 編譯，尚未宣稱新的實機結果。

出口條件：AC-08 通過，資料完全 local-only。

## Milestone 6：穩定化與 Release Candidate（3–5 天 + 8 小時 soak）

- [x] 62 JVM tests、14 instrumentation tests、Compose/Room/fake engine failure coverage
- [ ] API 26/34/36 完整 smoke matrix
- [ ] 非 Pixel 實體裝置背景與省電測試
- [ ] 8 小時 static mock soak test
- [ ] Start/Stop 20 次、鎖屏、Activity swipe-away、force-stop 測試
- [x] lint、license notice、R8 release build
- [x] release signing 僅由環境變數注入；無 key／secret 進版控
- [x] README：Unix／Windows 驗證、安裝、地圖供應、Developer Options、疑難排解
- [x] Privacy/Data safety 草稿
- [ ] Play Console foreground-service declaration/policy spike
- [ ] 關閉所有 P0/P1 問題並完成 MVP AC-01 至 AC-09

出口條件：產出可安裝 RC APK/AAB；是否公開上架另以 Play policy review 為準。

## 後續 Phase 2

- [x] `GeoCalculator`：distance/bearing/interpolation
- [ ] Joystick UI 與 500 ms movement loop
- [x] Cycling 固定 18 km/h
- [x] FOSSGIS OpenStreetMap bicycle demo router 作為無 API key 的 MVP 路徑來源
- [x] Route polyline、移動進度、Pause/Resume、Stop
- [x] 背景 route movement 與通知 Stop
- [x] Walking/Running/Driving/custom speed、smooth acceleration、end modes、GPS drift
- [x] Notification Pause/Resume/Stop route controls
- [x] 自動旅程以形狀控制點交由 FOSSGIS 規劃真實道路近似路線；無法規劃時沿用可重試錯誤

自行車路線切片（2026-08-24）：使用者設定起點與終點後，App 明確提示座標會送往 FOSSGIS，取得道路幾何並以 5.0 m/s（18 km/h）沿線注入 GPS + FLP。Sony 實機已驗證開始、持續移動、暫停座標不變、繼續移動、停止後移除服務／通知／mock provider；公共 demo router 沒有 SLA，正式流量仍需替換或自架。

路線 UX 更新（2026-08-24）：原本共用一顆動態按鈕的流程已拆成固定入口與「選起點 → 選終點 → 明確送出預覽 → 開始模擬」狀態。端點可直接使用準星或收藏，規劃中的請求可取消且舊回應不會覆蓋新選擇；橫向面板改為右側停靠，避免遮住中央準星。

## 後續 Phase 3

- [x] GPX import/export 與檔案驗證
- [ ] 收藏 JSON backup/restore
- [x] Saved routes 與 route points schema
- [x] 路線重新命名、複製、反向、刪除（大型 route simplification deferred）

## 建議迭代節奏

```text
Iteration 1  Core spike + notification Stop
Iteration 2  Map vertical slice + setup/permissions
Iteration 3  Search + favorites/recent
Iteration 4  hardening + RC
```

MVP 粗估 10–16 個有效工程日。最大的變數不是 Compose UI，而是不同 Android/OEM、前景服務政策與 Google Play 發佈驗證。

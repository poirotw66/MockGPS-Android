# 開發里程碑與任務清單

估算以一位熟悉 Kotlin/Android 的工程師為基準，只表示相對工作量；Play 審查、外部地圖服務與 OEM 相容性等待時間不包含在內。

## Gate 0：產品識別與外部資源（0.5–1 天）

- [x] 決定 App 顯示名稱：Mock GPS（繁中：模擬定位）
- [x] 決定不可隨意更改的 `applicationId`：`com.sora.mockgps`
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

實作進度（2026-08-23）：地圖垂直切片、Active 明確套用與完整 camera state 已完成，並改用不需要帳號或 API key 的 MapLibre + OpenFreeMap。仍需在實機完成圖磚載入、拖曳、樣式切換、旋轉與跨 App 出口驗證。

出口條件：拖地圖選點 → Start → 跨 App 驗證 → 通知 Stop 的完整流程通過。

## Milestone 3：設定與權限 UX（1–2 天）

- [ ] Setup screen：開發人員選項步驟與開啟設定
- [ ] Current-location shortcut 與 coarse/fine permission 流程
- [ ] API 33+ notification permission 說明
- [ ] FGS 啟動例外與 rollback
- [ ] Google Play services unavailable 狀態
- [ ] 地圖／搜尋服務的網路與供應商錯誤分類
- [ ] DataStore：map type、interval、accuracy、show coordinates、last coordinate
- [ ] 通知內容跟隨座標與設定更新
- [ ] 繁中／英文 string resources

出口條件：首次使用者能自行完成設定；拒絕任一可拒絕權限不造成 crash 或半 Active。

## Milestone 4：地點搜尋（1–2 天）

- [ ] 建立 `PlaceSearchRepository` 與 fake implementation
- [ ] 整合選定的 OSM 相容搜尋服務
- [ ] 管理 debounce、流量限制與取消過期請求
- [ ] 只要求名稱、地址、座標所需欄位
- [ ] 搜尋建議 UI、鍵盤操作、loading/empty/error
- [ ] 點結果後 animate camera 並更新 selection
- [ ] 測試快速輸入、清空、離線、rate-limit/error

出口條件：可搜尋 Tokyo Station 並成功開始該座標的靜態模擬。

## Milestone 5：收藏與最近位置（1–2 天）

- [ ] Room database、entities、DAO、repository
- [ ] 收藏新增、命名、重新命名、刪除、選取
- [ ] 成功 Active 後才 upsert recent
- [ ] 最近位置排序、去重、最多 50 筆
- [ ] 清除收藏／歷史確認流程
- [ ] 重啟 persistence 與第 51 筆裁切測試

出口條件：AC-08 通過，資料完全 local-only。

## Milestone 6：穩定化與 Release Candidate（3–5 天 + 8 小時 soak）

- [ ] JVM tests、Compose tests、Room tests、fake engine failure tests
- [ ] API 26/34/36 完整 smoke matrix
- [ ] 非 Pixel 實體裝置背景與省電測試
- [ ] 8 小時 static mock soak test
- [ ] Start/Stop 20 次、鎖屏、Activity swipe-away、force-stop 測試
- [ ] lint、dependency/license review、release build
- [ ] release signing key 與第三方服務設定審查
- [ ] README：安裝、地圖供應、Developer Options、疑難排解
- [ ] Privacy/Data safety 草稿
- [ ] Play Console foreground-service declaration/policy spike
- [ ] 關閉所有 P0/P1 問題並完成 MVP AC-01 至 AC-09

出口條件：產出可安裝 RC APK/AAB；是否公開上架另以 Play policy review 為準。

## 後續 Phase 2

- [ ] `GeoCalculator`：distance/bearing/destination/interpolation
- [ ] Joystick UI 與 500 ms movement loop
- [ ] Walking/Running/Cycling/Driving/custom speed
- [ ] Routes API 或替代路徑來源的產品／成本決策
- [ ] Route polyline、進度、Pause/Resume、變速、Stop
- [ ] 背景 route state 與通知控制

## 後續 Phase 3

- [ ] GPX import/export 與檔案驗證
- [ ] 收藏 JSON backup/restore
- [ ] Saved routes 與 route points schema
- [ ] 路線重新命名、複製、編輯、刪除

## 建議迭代節奏

```text
Iteration 1  Core spike + notification Stop
Iteration 2  Map vertical slice + setup/permissions
Iteration 3  Search + favorites/recent
Iteration 4  hardening + RC
```

MVP 粗估 10–16 個有效工程日。最大的變數不是 Compose UI，而是不同 Android/OEM、前景服務政策與 Google Play 發佈驗證。

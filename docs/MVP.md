# MVP 產品範圍與驗收

版本：0.1  
規劃日期：2026-08-23  
平台：Android 8.0+（API 26+）

## 1. 產品目標

讓開發者與 QA 在 Android 裝置上，用最短流程選擇一個座標並透過 Android 官方 Mock Location 機制持續模擬該位置。

核心體驗：

```text
開啟 App → 搜尋或拖曳地圖 → 選定座標 → Start → 背景持續 → Stop
```

成功指標：已完成首次設定的使用者，可在 10 秒內從首頁開始一個靜態位置模擬。

## 2. 使用對象

- 測試定位、Geofence、地區內容與距離功能的 Android 開發者
- 需要重現不同城市定位情境的 QA
- 需要固定、可重複測試座標的內部測試人員

## 3. MVP 必做範圍

### 3.1 地圖與選點

- MapLibre + OpenFreeMap 明亮／深色樣式切換
- 地圖中央固定準星；拖曳地圖，以 camera center 作為選定座標
- 顯示緯度、經度；至少保留小數點後 6 位
- 定位到裝置目前位置（只有在尚未模擬時作為真實定位快捷操作）
- 地圖載入失敗時顯示可重試狀態，不讓主 CTA 誤啟動未知座標

### 3.2 搜尋

- 以可替換的 `PlaceSearchRepository` 串接 OSM 相容地點搜尋服務
- 結果顯示名稱與格式化地址
- 選取後移動 camera，並更新選定座標
- 搜尋失敗、無結果、離線與服務限制錯誤有各自可理解的 UI 狀態

### 3.3 靜態 Mock Location

- Start 後每 1,000 ms 發送同一位置
- 每筆資料包含 latitude、longitude、accuracy、wall-clock time、monotonic elapsed realtime
- 同時支援 Android framework LocationManager 與 Google FLP；個別引擎失敗時保留診斷資訊
- Stop、服務銷毀、啟動失敗時，都要執行冪等 cleanup
- 明確顯示第三方 App 可以透過 `Location.isMock()` 拒絕這些位置

### 3.4 前景服務與通知

- 只能由可見 Activity 的明確使用者操作啟動服務
- 背景、鎖屏、Activity 被銷毀後仍持續模擬
- ongoing notification 顯示目前座標並提供 Stop action
- Notification Stop 不需重新打開 App
- 不在開機後或 crash 後自動恢復 Mock Location；重新啟動必須由使用者再次確認

### 3.5 首次設定與錯誤處理

- 引導使用者開啟開發人員選項並選擇本 App 為 Mock Location App
- 不假設系統有可靠 API 可預先讀出目前選定的 Mock App；Start 時以實際 API 結果為準
- 捕捉 `SecurityException`、invalid/incomplete location、Google Play services 不可用、前景服務啟動失敗
- 未設定 Mock App 時不得 crash，需提供開啟開發人員設定的操作

### 3.6 收藏、最近位置與設定

- 收藏：新增、命名、重新命名、刪除、選取
- 最近位置：模擬成功後才寫入，依最近使用時間排序，最多保留 50 筆
- 設定：地圖類型、更新頻率 500/1000/2000 ms、accuracy、是否顯示座標
- 所有資料只存在本機；提供清除收藏與清除歷史

## 4. 明確不做

- Joystick、路線規劃、Pause/Resume、GPX、路線保存：移到 Phase 2/3
- 帳號、雲端同步、廣告、分析 SDK
- Root、Xposed、Magisk、系統 hook
- 隱藏 `Location.isMock()`、規避遊戲／考勤／第三方反作弊偵測
- iOS、Web 版
- 未經使用者操作的開機或背景自動啟動

## 5. 核心畫面

1. Setup：開發人員選項說明與「開啟設定」
2. Map：搜尋、地圖、準星、座標、收藏、Start/Stop
3. Favorites：收藏與最近位置
4. Settings：地圖與 location payload 偏好
5. Active sheet：模擬中座標、更新頻率、Stop

MVP 不增加底部 Route／Joystick 分頁；未完成的功能不佔首頁導航。

## 6. 狀態與 UX 規則

```text
Idle
 ├─ Start → Starting → Active
 ├─ Start 未授權 → SetupRequired → Idle
 └─ Start 失敗 → Error → Idle

Active
 ├─ Stop → Stopping → Idle
 └─ Service failure → Error/Cleanup → Idle
```

- `Starting`、`Stopping` 時禁止重複點擊
- App 同一時間只能有一種模擬模式
- Active 時選點與搜尋可以瀏覽，但不得悄悄改變已注入座標；使用者需明確套用新位置
- UI 以服務實際狀態為準，不以先前儲存的 boolean 推測服務仍在執行

## 7. 驗收條件

### AC-01 選點

地圖停止移動後，選定座標等於 camera center；旋轉或重組畫面不遺失。

### AC-02 正常啟動

App 已被選為 Mock Location App 時，按 Start 後 3 秒內，LocationManager client 與 FLP client 都能觀察到目標座標（允許 10 m 誤差）。

### AC-03 未授權

App 未被選為 Mock Location App 時，按 Start 不 crash、不留下 ongoing service，並顯示設定引導。

### AC-04 背景持續

開始後切換到其他 App、關閉 Activity、關閉螢幕 10 分鐘，位置仍依設定頻率更新。

### AC-05 通知停止

從通知按 Stop 後 3 秒內：更新 loop 停止、FLP mock mode 關閉、test provider 移除、notification 消失。

### AC-06 重複操作

連續執行 Start → Stop 20 次，沒有 crash、重複 job、重複 notification 或殘留 test provider。

### AC-07 Process 結束

強制停止 App 後不得自行重新啟動模擬；下次開啟 UI 顯示 Idle。

### AC-08 收藏與最近位置

重啟 App 後收藏仍存在；只有成功開始的座標會加入最近位置；第 51 筆會淘汰最舊資料。

### AC-09 地圖供應與授權

地圖可在沒有帳號或 API key 的狀態載入；畫面保留 MapLibre、OpenFreeMap 與 OpenStreetMap 所要求的 attribution。若未來搜尋服務需要 token，版本庫不得包含真實 token。

## 8. 非功能需求

- 靜態模擬連續 8 小時無未預期停止（至少在一台 Pixel/Google API 裝置驗證）
- 非地圖網路載入造成的 cold start 目標低於 2 秒
- 不在主執行緒執行長時間工作；location loop 使用 structured coroutine
- 所有引擎 cleanup 都可安全重複呼叫
- 日誌不得記錄 token 或敏感設定；座標診斷 log 僅存在 debug build
- 介面至少支援繁體中文與英文，文字不得硬編碼於 Composable

## 9. MVP Definition of Done

- 所有 AC-01 至 AC-09 通過
- `assembleDebug`、unit tests、lint 通過
- API 26、API 34、API 36 完成啟停 smoke test
- 至少一台實體 Android 裝置完成 8 小時穩定測試
- 安裝、地圖供應、Mock Location 設定與疑難排解寫入 README
- 沒有 P0/P1 crash 或會讓裝置持續殘留 mock mode 的已知問題

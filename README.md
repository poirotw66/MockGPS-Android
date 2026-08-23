# GPS 定位模擬 App

Android Mock Location 工具。目前已完成靜態選點、喜愛地點，以及以 18 km/h 模擬自行車路線的可執行切片。

## 目前狀態

- Android 專案：Kotlin、Jetpack Compose、`com.sora.mockgps`
- SDK：min 26、compile/target 36
- 已完成：LocationManager GPS test provider、Fused Location mock mode、原子化 coordinator、前景服務、持續通知與 Stop action
- Map UI：Taipei 101 預設位置、中央準星選點、權限流程、開發人員選項入口、繁中／英文資源
- 地圖切片：MapLibre + OpenFreeMap、中央準星、明亮／深色樣式、完整 camera state、loading/error/retry、Active 明確套用新位置
- 喜愛地點：Room 本機儲存、新增、重新命名、選取、刪除與刪除確認
- 自行車路線：FOSSGIS OpenStreetMap 路由、道路 polyline、18 km/h 移動、暫停、繼續與停止
- 自動驗證：21 個 unit tests、`assembleDebug`、`lintDebug`（0 errors）
- 實機驗證：Sony XQ-BC72（Android 13 / API 33）靜態與路線 GPS + FLP 注入、背景持續、暫停／繼續、通知 Stop、20/20 次靜態啟停與完整清理均通過
- 待驗證：API 26/34/36 裝置矩陣與獨立 LocationManager/FLP client 的跨 App 讀值

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`

## 規劃文件

- [MVP 範圍與驗收](docs/MVP.md)
- [技術架構與平台決策](docs/ARCHITECTURE.md)
- [開發里程碑與任務清單](docs/TASKS.md)

## 已確定方向

- Kotlin、Jetpack Compose、單一 Android `app` module 起步
- `minSdk 26`、`compileSdk 36`、`targetSdk 36`
- MapLibre Compose 顯示 OpenFreeMap 向量地圖，不需要 Google Cloud、Billing 或 API key
- Android `LocationManager` 與 Google Play services Fused Location Provider 組成 Mock Location coordinator
- 使用前景服務維持模擬，通知提供立即停止操作
- Room 儲存喜愛地點；最近使用地點與 DataStore 偏好設定仍在後續里程碑
- 只採用 Android 官方 Mock Location 機制，不隱藏 Mock 狀態、不繞過第三方偵測

## 開工前需確定

以下選項不妨礙規劃，但在 Release Candidate 前必須定案：

1. App 顯示名稱
2. 永久 `applicationId`（目前：`com.sora.mockgps`）
3. 地點搜尋服務的供應商與使用政策
4. OpenFreeMap 可用性監控與未來自架策略
5. 至少一台可開啟「開發人員選項 → 選取模擬位置應用程式」的測試裝置

目前地圖不需要 API key。畫面保留 MapLibre／OpenStreetMap attribution；若未來搜尋服務需要 token，仍必須放在未追蹤的本機設定並限制使用範圍。

## 地圖服務

專案使用 MapLibre Compose 與 OpenFreeMap 的 OpenStreetMap 向量圖磚，預設提供 Positron 明亮樣式與 Dark 深色樣式。不需要帳號、信用卡或 API key。OpenFreeMap 是公共服務且沒有 SLA；若產品流量增長，應評估自架或可提供 SLA 的相容供應商。

## 自行車路由服務

路線規劃使用 FOSSGIS 維護的 OpenStreetMap bicycle demo router，不需要 API key。只有使用者按下「規劃自行車路線」時，起點與終點座標才會送往該服務；App 不會自動重試。公共 demo 服務限制每秒最多一次請求、要求可識別的 User-Agent，且不提供正式 SLA，因此目前只適合開發與輕量測試，正式大量使用前應改為自架或採用具 SLA 的服務。

## 第一個可交付切片

第一個實作批次已完成程式碼；核心鏈路已在 Android 13 實機通過，獨立定位 App 的讀值驗證仍待測：

```text
輸入固定座標
  → 使用者將 App 選為 Mock Location App
  → Start 前景服務
  → LocationManager + FLP 注入位置
  → 另一個定位 App 讀到該位置
  → 從通知 Stop
  → Mock mode 與 test provider 被完整清除
```

這條鏈完成後，再接地點搜尋、收藏與設定。

## 本機建置

需要 JDK 17 與 Android SDK Platform 36，並在不進版控的 `local.properties` 設定 `sdk.dir`。

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

安裝到已連線裝置：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

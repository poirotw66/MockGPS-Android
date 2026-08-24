# BloomWalk GPS

Android Mock Location 工具。目前已完成靜態選點、喜愛地點，以及可編輯、儲存與自訂移動行為的路線模擬。

## 目前狀態

- Android 專案：Kotlin、Jetpack Compose、application ID `com.bloss0m.bloomwalk`
- SDK：min 26、compile/target 36
- 已完成：LocationManager GPS test provider、Fused Location mock mode、原子化 coordinator、前景服務、持續通知與 Stop action
- Map UI：Taipei 101 預設位置、中央準星選點、權限流程、開發人員選項入口、繁中／英文資源
- 地圖切片：MapLibre + OpenFreeMap、中央準星、明亮／深色樣式、完整 camera state、loading/error/retry、Active 明確套用新位置
- 喜愛地點與最近位置：Room 本機儲存、Active 後寫入、六位小數去重、50 筆上限與清除確認
- 路線規劃：FOSSGIS OpenStreetMap 路由、A/B 與中繼點、排序／刪除／交換端點、道路 polyline
- 路線模擬：步行／跑步／自行車／駕車／自訂速度、平滑加減速、停止／循環／原路返回、可選 GPS 漂移
- 路線 UX：權威 Running/Paused/Completed/Failed 狀態、通知暫停／繼續、地圖即時位置、距離／時間／進度
- 路線資料：Room 儲存與最近使用、反向路線、GPX 匯入匯出、JSON 備份還原
- 韌性：有界路線快取、網路失敗 stale fallback、可替換 routing provider 設定與 service session token
- 自動驗證：JVM、Room migration／Compose/service instrumentation tests、`assembleDebug`、`lintDebug`、R8 release APK/AAB；CI 以 API 26、34、36 Google APIs emulator matrix 執行 instrumentation
- 實機證據：Sony XQ-BC72（Android 13 / API 33）已完成核心 Start/Stop 與 notification 行為驗證；API 26/34/36 與獨立 LocationManager／FLP client 跨 App 讀值仍需依下方手動程序驗證

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
- Room 儲存喜愛地點、已儲存路線與最近路線
- 只採用 Android 官方 Mock Location 機制，不隱藏 Mock 狀態、不繞過第三方偵測

## Release 與手動驗證

Google Play Console、Data Safety、foreground-service declaration、privacy policy URL、Internal Testing 軌道與審查送件都需要帳號擁有者操作；本專案沒有宣稱已完成這些外部動作。每個 release 前：更新 `versionCode`/`versionName`、以受保護環境變數簽署 AAB、上傳 Internal Testing、完成 Data Safety 和 FGS declaration，並發布可公開存取的隱私權政策 URL。

JSON backup 僅包含 saved/recent routes，**不包含 favorites**；這維持 v1 格式與 route database restore 的原子性。

### 手動裝置矩陣

在 API 26、34、36 Google APIs emulator，以及至少一台 OEM 實機（已有 Sony API 33 evidence）執行：選 BloomWalk GPS 為 mock location app，Start static mock，使用獨立 LocationManager 和 FLP client 讀取目標座標，Stop 並確認通知、test provider 和 FLP mock mode 都消失。可用：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.bloss0m.bloomwalk/com.sora.mockgps.MainActivity
adb shell dumpsys activity services com.sora.mockgps/.service.MockLocationForegroundService
adb shell dumpsys notification --noredact | grep -i bloomwalk
```

Reliability checklist（需記錄實際裝置、API、結果，未在本文件宣稱完成）：8-hour static soak；10-minute lockscreen；swipe-away Activity；force-stop（服務不得自行恢復）；notification Pause/Resume/Stop；20 次 Start/Stop；上述 `dumpsys` residue checks；OEM battery optimization on/off。Route scenarios must also confirm pause freezes coordinates and resume continues them.

## 開工前需確定

以下選項不妨礙規劃，但在 Release Candidate 前必須定案：

1. 地點搜尋服務的供應商與使用政策
2. OpenFreeMap 可用性監控與未來自架策略
3. 至少一台可開啟「開發人員選項 → 選取模擬位置應用程式」的測試裝置

目前地圖不需要 API key。畫面保留 MapLibre／OpenStreetMap attribution；若未來搜尋服務需要 token，仍必須放在未追蹤的本機設定並限制使用範圍。

## 地圖服務

專案使用 MapLibre Compose 與 OpenFreeMap 的 OpenStreetMap 向量圖磚，預設提供 Positron 明亮樣式與 Dark 深色樣式。不需要帳號、信用卡或 API key。OpenFreeMap 是公共服務且沒有 SLA；若產品流量增長，應評估自架或可提供 SLA 的相容供應商。

## 自行車路由服務

路線規劃預設使用 FOSSGIS 維護的 OpenStreetMap bicycle demo router，不需要 API key。只有使用者按下規劃時，依序排列的路線座標才會送往該服務。App 會使用短期記憶體快取，網路失敗時可回退至同一組路線點的舊快取。公共 demo 不提供正式 SLA，因此目前只適合開發與輕量測試。

`RoutingProviderConfig` 可換成自架 OSRM bicycle-compatible endpoint，並可設定 User-Agent 與 timeout；`CachingRoutingRepository` 以 provider URL 與 ordered waypoints 隔離 cache key。正式大量使用時，應在 `MapViewModel` 的 composition root 注入自架 endpoint 或具 SLA 的供應商。

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

macOS／Linux：

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
./gradlew assembleRelease bundleRelease
```

Windows PowerShell（保留原本驗證方式）：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
.\gradlew.bat assembleRelease
```

正式簽署只從 CI／本機環境變數讀取，不將 keystore 或密碼提交進版控：

```bash
export MOCKGPS_KEYSTORE_FILE=/absolute/path/release.jks
export MOCKGPS_KEYSTORE_PASSWORD='...'
export MOCKGPS_KEY_ALIAS='...'
export MOCKGPS_KEY_PASSWORD='...'
./gradlew bundleRelease
```

安裝到已連線裝置：

macOS／Linux：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Windows PowerShell：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

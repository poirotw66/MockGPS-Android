# BloomWalk GPS

[English](README.md)

Android Mock Location 工具。支援靜態選點、喜愛地點、路線模擬，以及獨立的搖桿自由移動。

## 目前狀態

- Android 專案：Kotlin、Jetpack Compose、application ID `com.bloss0m.bloomwalk`
- SDK：min 26、compile/target 36
- 已完成：LocationManager GPS test provider、Fused Location mock mode、原子化 coordinator、前景服務、持續通知與 Stop action
- Map UI：MapLibre + OpenFreeMap、中央準星選點、明亮／深色樣式、目前位置按鈕、繁中／英文資源
- 搜尋：Nominatim 遠端搜尋 + 91 個離線著名景點 + 座標直接解析（如 `25.033964, 121.564468`）
- 喜愛地點與最近位置：Room 本機儲存、Active 後寫入、六位小數去重、50 筆上限
- 路線規劃：FOSSGIS OpenStreetMap 路由、A/B 與中繼點、道路 polyline 預覽
- 路線模擬：步行／跑步／自行車／駕車／自訂速度、平滑加減速、停止／循環／原路返回、可選 GPS 漂移
- 自動旅途：台灣／日本／韓國景點（含濟州島）或**目前位置**；**完美圖形**或**適配道路**兩種路線模式
- **搖桿（獨立功能）**：底部 Dock「搖桿」分頁，6 檔速度（步行 5、跑步 10、單車 18、汽車 100、高鐵 300、飛機 1000 km/h）
- 路線資料：Room 儲存、反向路線、GPX 匯入匯出、JSON 備份還原（不含收藏地點）
- 自動驗證：JVM + instrumentation tests、`assembleDebug`、`lintDebug`、R8 release
- 實機證據：Sony XQ-BC72（Android 13）已驗證核心流程；API 26/34/36 矩陣仍待完整執行

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`

## 規劃文件

- [MVP 範圍與驗收](docs/MVP.md)
- [技術架構與平台決策](docs/ARCHITECTURE.md)
- [開發里程碑與任務清單](docs/TASKS.md)

## 已確定方向

- Kotlin、Jetpack Compose、單一 Android `app` module
- `minSdk 26`、`compileSdk 36`、`targetSdk 36`
- MapLibre Compose + OpenFreeMap，不需要 Google Cloud API key
- LocationManager + Fused Location Provider 組成 Mock Location coordinator
- 前景服務維持模擬，通知提供 Stop（路線另有 Pause/Resume）
- Room 儲存喜愛地點、已儲存路線與最近路線
- 只採用 Android 官方 Mock Location 機制，不隱藏 Mock 狀態

## Release 與手動驗證

Google Play Console、Data Safety、foreground-service declaration、privacy policy URL 需帳號擁有者操作。每個 release 前：更新 `versionCode`/`versionName`、簽署 AAB、上傳 Internal Testing。

JSON backup 僅包含 saved/recent routes，**不包含 favorites**。

### 手動裝置矩陣

在 API 26、34、36 emulator 與 OEM 實機執行：選 BloomWalk GPS 為 mock location app → Start → 獨立 LocationManager/FLP client 讀值 → Stop 並確認清理完成。

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.bloss0m.bloomwalk/com.sora.mockgps.MainActivity
adb shell dumpsys activity services com.bloss0m.bloomwalk/.service.MockLocationForegroundService
```

若「選取模擬位置應用程式」沒有顯示 BloomWalk GPS：

```bash
adb shell dumpsys package com.bloss0m.bloomwalk | grep ACCESS_MOCK_LOCATION
adb shell appops set com.bloss0m.bloomwalk android:mock_location allow
```

Reliability checklist（需記錄結果）：8 小時 static soak、鎖屏、swipe-away Activity、force-stop、20 次 Start/Stop、通知 Pause/Resume/Stop、OEM 省電模式。

## 開工前需確定

1. 地點搜尋服務的供應商與使用政策
2. OpenFreeMap 可用性監控與未來自架策略
3. 至少一台可開啟「開發人員選項 → 選取模擬位置應用程式」的測試裝置

## 地圖服務

MapLibre Compose + OpenFreeMap 向量圖磚（Positron / Dark）。不需要帳號或 API key。OpenFreeMap 為公共服務、無 SLA。

## 路由服務

預設 FOSSGIS OpenStreetMap demo router，不需要 API key。僅在使用者按下規劃時送出座標。可透過 `RoutingProviderConfig` 換成自架 endpoint。

## 本機建置

需要 JDK 17 與 Android SDK Platform 36，並在 `local.properties` 設定 `sdk.dir`。

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
./gradlew assembleRelease bundleRelease
```

正式簽署從環境變數讀取：

```bash
export MOCKGPS_KEYSTORE_FILE=/absolute/path/release.jks
export MOCKGPS_KEYSTORE_PASSWORD='...'
export MOCKGPS_KEY_ALIAS='...'
export MOCKGPS_KEY_PASSWORD='...'
./gradlew bundleRelease
```

安裝到裝置：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

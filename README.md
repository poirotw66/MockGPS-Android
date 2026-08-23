# GPS 定位模擬 App

Android Mock Location 工具。Milestone 1 核心 Spike 已完成程式碼、單元測試、Debug APK、Android Lint，以及 Android 13 實機核心驗證。

## 目前狀態

- Android 專案：Kotlin、Jetpack Compose、`com.sora.mockgps`
- SDK：min 26、compile/target 36
- 已完成：LocationManager GPS test provider、Fused Location mock mode、原子化 coordinator、前景服務、持續通知與 Stop action
- Spike UI：Taipei 101 預設座標、自訂經緯度、權限流程、開發人員選項入口、繁中／英文資源
- 自動驗證：8 個 unit tests、`assembleDebug`、`lintDebug`（0 errors）
- 實機驗證：Sony XQ-BC72（Android 13 / API 33）GPS + FLP 注入、背景持續、通知 Stop、20/20 次啟停與完整清理均通過
- 待驗證：API 26/34/36 裝置矩陣與獨立 LocationManager/FLP client 的跨 App 讀值

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`

## 規劃文件

- [MVP 範圍與驗收](docs/MVP.md)
- [技術架構與平台決策](docs/ARCHITECTURE.md)
- [開發里程碑與任務清單](docs/TASKS.md)

## 已確定方向

- Kotlin、Jetpack Compose、單一 Android `app` module 起步
- `minSdk 26`、`compileSdk 36`、`targetSdk 36`
- Google Maps Compose 顯示地圖；Places SDK for Android 提供地點搜尋
- Android `LocationManager` 與 Google Play services Fused Location Provider 組成 Mock Location coordinator
- 使用前景服務維持模擬，通知提供立即停止操作
- Room 儲存收藏與最近使用地點，DataStore 儲存偏好設定
- 只採用 Android 官方 Mock Location 機制，不隱藏 Mock 狀態、不繞過第三方偵測

## 開工前需確定

以下選項不妨礙規劃，但在建立 Google Cloud API key 前必須定案：

1. App 顯示名稱
2. 永久 `applicationId`（目前：`com.sora.mockgps`）
3. Google Cloud 專案與帳務設定
4. Maps SDK for Android、Places API (New) 的受限 API key
5. 至少一台可開啟「開發人員選項 → 選取模擬位置應用程式」的測試裝置

API key 不進版控；Android key 必須限制到正式 package name、簽章 SHA-1，以及實際使用的 Maps/Places API。

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

這條鏈完成後，再接 Google Map、搜尋、收藏與設定。

## 本機建置

需要 JDK 17 與 Android SDK Platform 36，並在不進版控的 `local.properties` 設定 `sdk.dir`。

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

安裝到已連線裝置：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

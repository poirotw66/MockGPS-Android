# 技術架構與平台決策

## 1. 架構概覽

MVP 採單一 Gradle `app` module、依 feature/package 分層。先保持編譯與導覽簡單；當 Phase 2 路線模擬讓 build time 或責任邊界明顯增長時，再拆 module。

```text
Compose UI
  │ user intent / immutable UiState
  ▼
ViewModel
  │
  ├──────────────► Search / Favorites / Settings repositories
  │
  ▼
MockLocationServiceController
  │ explicit Intent commands: START, UPDATE, STOP
  ▼
MockLocationForegroundService
  │ StateFlow<ServiceState> + one update coroutine
  ▼
MockLocationCoordinator
  ├── FrameworkMockEngine (LocationManager)
  └── FusedMockEngine (FusedLocationProviderClient)
```

## 2. 建議 package

在永久 `applicationId` 確定後，使用以下結構：

```text
<applicationId>/
├── app/                 Application、MainActivity、navigation
├── core/model/          Coordinate、MockPayload、SimulationState
├── core/location/       engine contracts、payload factory、coordinator
├── service/             foreground service、notification、commands
├── feature/setup/       Developer Options 引導
├── feature/map/         MapScreen、MapViewModel、搜尋
├── feature/search/      可替換的地點搜尋 adapter
├── feature/saved/       收藏與最近位置
├── feature/settings/    DataStore settings
└── data/local/          Room entities、DAO、database
```

## 3. 核心資料模型

避免讓 MapLibre `Position` 或其他地圖 SDK 型別滲入 domain/service：

```kotlin
data class Coordinate(
    val latitude: Double,
    val longitude: Double,
)

data class MockPayload(
    val coordinate: Coordinate,
    val accuracyMeters: Float,
    val altitudeMeters: Double?,
    val speedMetersPerSecond: Float,
    val bearingDegrees: Float,
    val wallClockMillis: Long,
    val elapsedRealtimeNanos: Long,
)
```

`MockPayloadFactory` 每次 tick 重新建立時間欄位，保證 wall clock 與 elapsed realtime 單調前進；不重用舊的 `Location` instance。

## 4. Engine contract

```kotlin
interface MockLocationEngine {
    suspend fun start()
    suspend fun push(payload: MockPayload)
    suspend fun stop()
}
```

規則：

- `start()`、`stop()` 必須冪等
- Coordinator 固定 start 所有引擎；若任一引擎失敗，停止已啟動者並回報整體失敗，避免半套 Active 狀態
- 每個 tick 可個別記錄引擎錯誤；連續錯誤達門檻時停止整體服務並 cleanup
- `stop()` 使用 `try/finally`，FLP 一律嘗試 `setMockMode(false)`，framework 一律嘗試 disable/remove test provider
- API 31+ 使用 `ProviderProperties` overload；API 26–30 使用舊 overload

第一個 spike 驗證 `GPS_PROVIDER + FLP`。只有當測試矩陣證明特定 client 需要時，才加入 `NETWORK_PROVIDER`，避免無證據地擴大替換系統 provider 的範圍。

## 5. Foreground Service

### 命令

Service 只接受明確、可序列化的小型命令：

- `ACTION_START`：coordinate + settings snapshot
- `ACTION_UPDATE`：新的 coordinate + settings snapshot
- `ACTION_STOP`

不把 ViewModel 或 repository instance 傳入 Service。服務維持唯一 update `Job`；收到新 Start 前先取消舊 Job，防止雙重 loop。

### 生命週期

- Activity 可見、使用者按 Start 後才呼叫 `startForegroundService()`
- Service 在系統期限內立即建立 notification 並 `startForeground()`，再啟動 engine
- 使用 `START_NOT_STICKY`：系統終止後不自動恢復定位模擬
- `onDestroy()`、Stop action、engine fatal error 共用同一個 cleanup path
- 不從 `BOOT_COMPLETED` 啟動

### Foreground service type

目標 API 36 時所有 FGS 都必須宣告合適 type。初版以 `location` type 規劃，Manifest 宣告 `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_LOCATION`，並在可見 Activity 中取得 coarse/fine location runtime permission 後才啟動。Android 官方指出 location FGS 受 while-in-use 限制，因此背景不能任意新啟動；本產品只讓已啟動的服務持續執行。

Mock 注入本身是否能以更窄的 `specialUse` type 通過實際 Play Console declaration，列為發佈前 policy spike；在未有 Play 審查證據前不把它當成既定方案。

## 6. 權限矩陣

| 權限／能力 | 用途 | MVP 行為 |
|---|---|---|
| `INTERNET` | OpenFreeMap 圖磚與未來地點搜尋 | Manifest normal permission |
| `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` | Current-location shortcut、location FGS prerequisite | 首次使用相關功能時請求 |
| `FOREGROUND_SERVICE` | 長時間執行 | Manifest |
| `FOREGROUND_SERVICE_LOCATION` | location FGS type | Manifest，API 34+ |
| `POST_NOTIFICATIONS` | 顯示 ongoing notification 與 Stop | API 33+ 請求；拒絕時說明限制 |
| Mock Location app selection | 注入測試位置 | 使用者在 Developer Options 手動選擇；不是一般 runtime permission |
| `ACCESS_BACKGROUND_LOCATION` | 從背景新啟動 location FGS | MVP 不請求，也不支援背景新啟動 |

## 7. 狀態來源

`ServiceState` 是執行中狀態的唯一真相：

```text
Idle → Starting → Active → Stopping → Idle
           └──────► Error/Cleanup ─────┘
```

- ViewModel 觀察 service/controller flow，不能靠 DataStore 的 `isMocking=true` 還原 Active
- DataStore 只存 UI 偏好與最後選定座標，不存「服務仍在執行」的事實
- Activity 重建時重新訂閱狀態
- Service 被終止後下次 UI 一律 Idle，不自動重播最後命令

## 8. 地圖與搜尋

- MapLibre Compose 以 `CameraState` 取得 camera center
- 只在 camera idle 時更新選定座標，拖曳中不高頻觸發 reverse lookup
- MVP 不需要 reverse geocoding camera center；無 place name 時顯示座標，避免額外 API 成本與延遲
- 地圖使用 OpenFreeMap 的 Positron／Dark 樣式，保留 MapLibre、OpenFreeMap 與 OpenStreetMap attribution
- 搜尋透過可替換介面包裝 OSM 相容服務，只保留名稱、格式化地址與座標
- 搜尋必須遵守供應商的 rate limit，並對文字輸入做 debounce/cancellation；unit test 使用 fake repository

## 9. 地圖供應與設定

- MapLibre Compose 負責 Android 地圖渲染，OpenFreeMap 提供 OSM 向量圖磚與樣式
- 目前不需要 Google Cloud、Billing、Secrets Plugin 或 API key
- style URL 集中定義，不硬散落在畫面邏輯，方便切換相容供應商或自架
- OpenFreeMap 為無 SLA 的公共服務；Release 前需記錄可用性風險與替代策略
- 未來搜尋服務若需要 token，必須從不進版控的本機設定注入，且不得與其他服務共用

## 10. 本機資料

### Room

```text
favorite_location
- id: Long PK
- name: String
- latitude: Double
- longitude: Double
- created_at: Long
- updated_at: Long

recent_location
- id: Long PK
- latitude: Double
- longitude: Double
- normalized_latitude: Long
- normalized_longitude: Long
- used_at: Long
```

- 對標準化後的座標建立唯一策略，避免同一地點反覆新增
- 最近位置只在 service 成功進入 Active 後 upsert
- transaction 中保留最新 50 筆並刪除其餘資料

### DataStore

`AppSettingsRepository` 以 Preferences DataStore 保存地圖樣式、更新間隔、精度、座標顯示偏好與最後一次真正 Active 的座標。它絕不保存服務 Active 旗標；服務事實一律由前景服務的 state flow 提供。

### 外部搜尋與路由

`NominatimPlaceSearchRepository` 與 `FossgisBicycleRoutingRepository` 是可替換 adapter。兩者都有可注入設定與 bounded HTTP body；公共端點以 mutex 節流到每秒最多一個請求，離線不可用且沒有 SLA。搜尋輸入由 ViewModel debounce 350ms，新的輸入會取消舊工作，並以 typed error 對應本地化畫面訊息。

路線資料庫的 observable library query 只投影 id、名稱、距離與時間摘要；geometry 僅在載入、反向、備份或實際模擬時按需解碼，避免清單更新時重複配置完整 polyline。

- map type
- update interval
- accuracy
- show coordinates
- last selected coordinate

## 11. 測試策略

### JVM unit tests

- 座標驗證（latitude -90..90、longitude -180..180）
- payload 時間、accuracy、speed、bearing
- state reducer 與重複 Start/Stop
- repository sorting、upsert、50 筆裁切
- ViewModel 搜尋 debounce、stale result cancellation

### Instrumentation / fake tests

- Compose：準星、camera idle、loading/error、Start/Stop disabled states
- Room migration 與 persistence
- Notification Stop PendingIntent
- Engine contract 以 fake engine 測 failure cleanup

### 裝置整合測試

Mock Location App selection 與跨 App 觀察必須在 emulator/實體裝置手動或半自動測試：

| 裝置 | 目的 |
|---|---|
| API 26 emulator/device | minSdk 舊 overload 與通知行為 |
| API 34 | FGS type、notification/location permission |
| API 36 Google APIs image | target 行為、FLP、MapLibre |
| 一台非 Pixel 實體裝置 | OEM 省電與背景穩定性 |

## 12. 主要風險與處理

| 風險 | 影響 | 處理 |
|---|---|---|
| OEM 或 client 對 provider 行為不同 | 某些 App 不收到位置 | 先做 GPS+FLP spike；以裝置矩陣記錄，不承諾繞過拒絕 |
| FGS 啟動／權限限制 | 背景啟動 crash | 只從可見 Activity 啟動；完整捕捉與 rollback |
| Stop 未完整 cleanup | 裝置殘留 mock mode | 單一冪等 cleanup path、重複啟停與 force-stop 測試 |
| 公共圖磚服務中斷或政策改變 | 地圖無法載入 | error/retry、集中 style URL、保留相容供應商與自架選項 |
| 把 UI boolean 當服務狀態 | Zombie UI | ServiceState 為唯一真相，不持久化 Active |
| Play policy/FGS declaration 不符 | 無法上架 | Release 前做 Play Console policy spike；內部 APK 不等同已核准上架 |

## 13. 目前依據的官方文件

- [Android LocationManager test provider](https://developer.android.com/reference/android/location/LocationManager)
- [FusedLocationProviderClient mock mode](https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient)
- [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Android foreground service changes](https://developer.android.com/develop/background-work/services/fgs/changes)
- [MapLibre Compose](https://maplibre.org/maplibre-compose/)
- [MapLibre Compose interaction](https://maplibre.org/maplibre-compose/interaction/)
- [OpenFreeMap Quick Start](https://openfreemap.org/quick_start/)
- [OpenStreetMap attribution](https://www.openstreetmap.org/copyright)
- [Google Play target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en-GB_ALL)

查核日期為 2026-08-23。MapLibre Compose 版本由 version catalog 鎖定，OpenFreeMap 樣式 URL 集中管理；2026-08-31 起 Google Play 新 App／更新需 target Android 16（API 36）或以上。依賴與圖磚供應政策在升級 PR 重新驗證。

# BloomWalk GPS：零固定服務費與營利版本實作規格

- Status: Ready for implementation; external launch gates remain open
- Spec version: 1.0
- Date: 2026-09-28
- Repository baseline: `f333dc4`
- Application ID: `com.bloss0m.bloomwalk`
- Android namespace: `com.sora.mockgps`
- Platforms: Android 10+; current minSdk 29 / targetSdk 36
- Scope: Android application, API gateway, purchase verification, advertising, release validation

本規格是下一階段實作的權威文件，不代表服務已部署、交易已驗證或 Google Play 已核准。與舊架構文件的公共搜尋、自動連網、金鑰注入或路由供應方式衝突時，以本規格為準；實作完成時同步更新舊文件。

## 1. 產品決策與成本邊界

### 1.1 已決定的產品模式

1. App 免費下載，核心功能全部保留。
2. 免費版使用 AdMob 橫幅廣告；第一版不加入插頁、開屏、獎勵式廣告。
3. 提供 Google Play 一次性非消耗型商品 `remove_ads_lifetime`，永久移除 App 廣告，沒有訂閱。
4. 付費版與免費版使用相同線上搜尋／路由公平使用限制。付費不代表無限 API、離線地圖、優先服務或供應商 SLA。
5. 保留 MapLibre + OpenFreeMap；搜尋與道路路由改由自有 gateway 接 Geoapify。
6. Cloudflare Workers Free + SQLite-backed Durable Objects 作為小型後端；使用免費 `workers.dev` 網域，不要求購買網域。
7. 不建立 BloomWalk 使用者帳號、不加社群登入、不新增付費分析或訂閱管理服務。
8. 啟用任何付費方案、自動加購或付費基礎設施都不在此規格授權範圍。

### 1.2 「零成本」的準確定義

目標是初期 **零固定服務費且不自動產生 API 超額帳單**，不是無上限流量、零維運或所有費用皆為零。既有裝置／網路、Play 開發者註冊費、交易服務費及適用稅務不包含在零固定服務費承諾。

官方價格查核快照如下，部署與每次公開發布前必須重新核對 [S1–S4]：

| Service | Checked free allowance | Operational rule |
|---|---|---|
| Geoapify | 3,000 credits/day; up to 5 requests/second | 允許商業使用，必須 attribution；免費額度是帳號共用 |
| Workers Free | 100,000 requests/day | 保持 Free；達上限接受暫停服務 |
| SQLite Durable Objects Free | 100,000 requests/day; 13,000 GB-s/day | 另有 5 million rows read/day、100,000 rows written/day、5 GB storage 限制 |
| OpenFreeMap | 公共地圖服務 | 不承諾離線可用或可用性 SLA |

不得把這些額度相加當成總容量。DO 呼叫、儲存讀寫與刪除、簽章 CPU、重試都會消耗各自資源。免費層耗盡、濫用、停權或供應商政策改變都可能使線上功能不可用。

**強制要求：** Gateway 計數必須保守且持久化，但程式計數本身不等於供應商的硬帳單上限。Geoapify 免費方案的 quota 是 soft limit [S1]；啟用正式服務前需確認帳號沒有自動付費途徑，並確認本規格第 6 節的路由費用上界。

### 1.3 不做的功能

- 自架全球 Nominatim／OSRM 資料庫、離線圖磚下載。
- 訂閱、點數包、觀看廣告換搜尋額度、付費搜尋優先權。
- 隱藏 mock 狀態、繞過其他 App 偵測、承諾所有 App 接受模擬位置。
- 額外使用者帳號、跨裝置同步收藏、廣告個人化所需的自建位置資料庫。
- 以公共 Nominatim／FOSSGIS demo 當自動備援。

## 2. 現有架構與修改位置

| Existing component | Observed behavior | Required change |
|---|---|---|
| `feature/map/MapViewModel.kt` | 文字改變後 delay 350ms，呼叫遠端搜尋 | 分開本機輸入事件與明確提交搜尋事件 |
| `feature/map/PlaceSearchContent.kt` | IME Search 只隱藏鍵盤 | IME 與可見搜尋按鈕共用提交事件 |
| `feature/search/PlaceSearchRepository.kt` | 直接使用公共 Nominatim | 保留本機座標／景點邏輯，新增 gateway adapter |
| `feature/map/MapDependencies.kt` | production composition root 直接建立公共服務 adapter | 注入 gateway repositories 與共用 client |
| `route/BicycleRoutingRepository.kt` | `RouteTransportMode` 綁定 FOSSGIS URL | domain enum 保留 Walk/Bicycle/Drive；供應商 URL 移出 domain |
| `route/CachingRoutingRepository.kt` | 快取鍵包含供應商 base URL | 改為 contract/provider revision、mode、完整有序座標及 options |
| `feature/map/AutomaticJourneyRoutePlanner.kt` | 最多四次縮小圖形並重新規劃 | 保留最多四次行為，每次均經後端准入，耗盡時停止 |
| `MainActivity.kt` / `MapScreen.kt` | 無 billing／ads application composition | 新增 app-level monetization dependencies，不塞進定位 service |
| `core/settings/AppSettingsRepository.kt` | 儲存 UI 偏好 | 保持原責任；購買權益另設 store |
| `PRIVACY.md` | 工程資料盤點，宣稱無廣告／後端 | 改成實際資料盤點，另提供公開正式隱私網頁 |
| `.github/workflows/android.yml` | Android debug/release + instrumentation | 加入 gateway tests 與 release configuration checks |

保留既有 Room 資料、匯入匯出格式與 application ID。既有保存的路線不能因供應商改變而失效。需要新增 provenance 時提供 Room migration，不刪表重建。

## 3. 執行架構與責任

```text
Android Compose UI
  ├─ Local landmarks / coordinate parser / Room / mock service
  ├─ SearchRepository / RoutingRepository ── HTTPS ── API Worker
  │                                              ├─ GeoBudget Durable Object
  │                                              └─ Geoapify adapter
  ├─ BillingRepository ── Google Play Billing
  │                  └── HTTPS ── API Worker ── Purchase Durable Object
  │                                               └─ Google Play Developer API
  └─ AdsController + ConsentRepository ── AdMob / UMP

MapLibre ── OpenFreeMap (independent of gateway quota)
```

- Gateway 不產生模擬位置、不控制 foreground service、不接收播放中的位置串流。
- 只有明確的搜尋／規劃操作可以觸發地理服務；播放、移動搖桿、每次 GPS tick 不呼叫 gateway。
- 廣告、購買、配額與網路故障不得阻止 Start/Stop/Pause/Resume、本機資料或已下載路線播放。
- 不以購買 token 做搜尋身分識別；營利與地理服務配額分離。
- 不以 Worker isolate memory 或 eventual-consistent KV 執行精確全域計數。
- 第一版不建立 gateway 地理結果共享快取，避免位置保存與授權複雜度；保留有界 App 本機快取。

### 3.1 預計新增結構

```text
app/src/main/java/com/sora/mockgps/
  core/network/       GatewayClient, GatewayError, GatewayConfig
  feature/search/     GatewayPlaceSearchRepository
  route/              GatewayRoutingRepository
  monetization/
    billing/          BillingRepository, PlayBillingRepository, PurchaseVerifier
    entitlement/      EntitlementRepository, EntitlementStore, ReceiptVerifier
    ads/              AdsController, AdaptiveBanner
    consent/          ConsentRepository
    ui/               RemoveAdsScreen, AboutAndPrivacyScreen

gateway/
  src/index.ts
  src/contracts/
  src/providers/geoapify.ts
  src/geo-budget.ts
  src/purchase-verifier.ts
  src/purchase-state.ts
  src/config.ts
  test/
  package.json
  package-lock.json
  tsconfig.json
  wrangler.jsonc
  README.md
  README-tw.md
  .dev.vars.example
```

保持單一 Android app module，沿用手動 dependency injection。Gateway 採 TypeScript、嚴格型別、固定 lockfile、Wrangler 與 Workers 相容測試工具；不為本次工作更換無關 Android 工具鏈。

## 4. 使用者流程與介面

### 4.1 搜尋

1. 輸入文字立即解析座標與查找本機景點，不連網。
2. 未提交時顯示本機結果與「按搜尋查詢線上地點」，不能顯示「線上找不到結果」。
3. 點搜尋按鈕或 IME Search 才提交；空白、少於 2 個 Unicode code points 或超過 200 個 code points 不送出。
4. 可解析成合法座標的輸入直接選擇本機結果，不呼叫外部服務。
5. 提交時 snapshot query、語言、選用的地圖中心 bias。Bias 是使用者瀏覽位置，不自動讀取真實 GPS。
6. 使用遞增 submission ID 防止 A → B → A 的舊結果覆寫；不能只比對字串。
7. 新輸入取消 UI 等待；取消不能被視為供應商沒有計費。遠端已送出的請求仍按保守預算處理。
8. 同一提交進行中禁用重複送出，保留清除／修改輸入及本機結果操作。
9. 連網失敗保留本機結果，依錯誤分類顯示訊息。
10. 結果區展示供應商要求的可點擊來源標示。

State: `LocalOnly`, `Submitting`, `RemoteResults`, `EmptyRemoteResults`, `RemoteUnavailable`。遠端結果只能由明確提交改變，不能由 recomposition 或 Activity 恢復觸發。

### 4.2 路由與自動旅途

- 步行／自行車／駕車對應 `walk` / `bicycle` / `drive`。
- 手動規劃保留 2–25 個有序控制點；不默默丟點或改變交通模式。
- 本機「完美圖形」與既有 route playback 不需要 gateway。
- 「適配道路」保留最多四次既有縮小演算法，每次各消耗一次准入與可能的供應商 credits。禁止網路層另外再乘上透明重試。
- 若已有有效候選，但下一次因配額／暫時故障失敗，保留候選，顯示可能超過目標距離，讓使用者確認使用；不能假裝達到目標。
- 第一次規劃即失敗時保留輸入。可讓使用者明確切換本機圖形；不能自動將直線當成道路結果。
- 對線上距離限制給出明確錯誤；不能因此限制已保存、匯入或本機模擬的路線長度。

### 4.3 去廣告頁

提供永久可找到的「移除廣告」「恢復購買」「隱私設定」「關於與支援」入口。

| UI | Traditional Chinese | English |
|---|---|---|
| Product title | 永久移除廣告 | Remove ads permanently |
| Promise | 一次購買，無訂閱。移除此 App 的所有廣告。 | One-time purchase. No subscription. Removes all ads in this app. |
| Service boundary | 線上搜尋與道路規劃仍受服務可用性及公平使用限制。 | Online search and road routing remain subject to service availability and fair-use limits. |
| Restore | 恢復購買 | Restore purchases |
| Pending | 付款處理中，完成後將自動解鎖。 | Payment pending. Ad removal unlocks after completion. |
| Verification outage | 正在確認購買，請稍後再試。 | Your purchase is being verified. Please try again later. |
| Global quota | 線上服務今日額度已用完，本機功能仍可使用。 | Today's online service allowance is exhausted. Local features remain available. |
| Per-client limit | 請求過於頻繁，請稍後再試。 | Too many requests. Please try again later. |

價格必須來自當次有效 Play ProductDetails 的 localized formatted price，不硬寫貨幣或金額。價格尚未決定不阻止程式實作，以測試商品驗證；正式商品 base price 與銷售地區由擁有者在上架前決定。

商品讀取失敗時禁用購買並保留恢復入口。購買中不允許重複開啟 Billing flow。移除廣告成功後立即移除所有已載入／排程中的廣告。

## 5. Gateway API contract v1

### 5.1 共通約定

- HTTPS only；production 不接受任意 endpoint、HTTP、redirect 到未知 host。
- `Content-Type: application/json`，UTF-8；所有 payload 使用 English field names。
- 地理請求以 POST 傳送，避免 query／coordinates 出現在 gateway access URL。
- `X-Install-ID`: App 首次啟動產生的隨機 UUID，獨立於廣告 ID／Google 帳號；清除資料或重裝會改變。
- Install ID 不是認證或不可偽造的裝置識別。只用於輔助限流；成本防線是全域配額。
- `X-Client-Version`: 診斷用途，不作授權依據。
- `X-Request-ID`: UUID；在回應原樣回傳。不是付款 token，也不包含使用者資料。
- v1 POST 不自動 retry；重試由使用者明確操作產生新 request ID。
- Search body limit 4 KiB、route 16 KiB、purchase 16 KiB，超限在 parse 前拒絕；錯誤 body <= 4 KiB。
- Gateway 地理 response <= 512 KiB、Android bounded read 同步限制；search response <= 64 KiB。
- 地理成功回應 `Cache-Control: no-store`，由 App repository 明確控制記憶體快取。
- HTTP header/body 的 limit 同時涵蓋不可信 provider 回應，並檢查深度、陣列長度及有限浮點數。

### 5.2 `GET /v1/config`

回傳 public capabilities，不含秘密或精確剩餘總額度：

```json
{
  "schemaVersion": 1,
  "revision": "geoapify-v1",
  "searchEnabled": true,
  "routingEnabled": false,
  "purchasesEnabled": false,
  "adsEnabled": false,
  "routingMaxWaypoints": 25,
  "routingMaxInputDistanceMeters": 100000,
  "privacyPolicyUrl": "https://example.invalid/privacy",
  "supportUrl": "https://example.invalid/support"
}
```

例子中的 `.invalid` 必須被 release validation 拒絕。初始正式 feature flags 預設 false，完成各項 gate 才開啟。此 endpoint 可短暫公開快取 5 分鐘；App 啟動／回前景最多每 6 小時刷新。無設定時本機功能可用、廣告與新購買關閉，線上地理功能顯示暫不可用。已有有效快取能力設定最多使用 24 小時，後端仍逐請求執行真正的 kill switch。不可用遠端設定清除既有付費權益。

### 5.3 `POST /v1/search`

```json
{
  "query": "台北車站",
  "language": "zh-TW",
  "bias": { "latitude": 25.0478, "longitude": 121.5170 }
}
```

- `bias` optional；不接受 country filter 或 caller-provided URL。
- v1 language allowlist: `zh-TW`, `en`；供應商語言 mapping 在 adapter 內，需用 fixture 與 live sample 驗證。
- Normalize trim、Unicode NFC，不移除語意、不把繁中轉簡中。
- Server fixed result limit 8。

```json
{
  "requestId": "7fa29c99-6507-4618-94d8-4af824f57e64",
  "providerRevision": "geoapify-v1",
  "results": [
    {
      "name": "台北車站",
      "address": "臺北市",
      "coordinate": { "latitude": 25.0478, "longitude": 121.5170 }
    }
  ],
  "attribution": [
    { "label": "Powered by Geoapify", "url": "https://www.geoapify.com/" },
    { "label": "© OpenStreetMap contributors", "url": "https://www.openstreetmap.org/copyright" }
  ]
}
```

Attribution 文案依正式授權查核；URL allowlist，不把 provider 任意回傳的 HTML 當 UI。無結果為 200 + empty array，不是失敗。

### 5.4 `POST /v1/route`

```json
{
  "mode": "bicycle",
  "waypoints": [
    { "latitude": 25.033964, "longitude": 121.564468 },
    { "latitude": 25.0478, "longitude": 121.5170 }
  ]
}
```

- 有序 2–25 點；相鄰重複點拒絕，閉合路線首尾相同允許。
- latitude -90..90、longitude -180..180，全部 finite。
- v1 線上輸入相鄰點大圓距離總和 <= 100 km，這是濫用限制，**不是供應商計費距離上界**。
- 不接受 `avoid`、`elevation`、`route_details`、`optimize_stops` 或任意附加供應商參數。
- Response: `requestId`, `providerRevision`, `mode`, `points` (named latitude/longitude objects), `distanceMeters`, `providerDurationSeconds`, `attribution`。
- Geometry <= 10,000 points 且符合 body bound；不因超限回傳截斷後的斷路線。可使用既有簡化邏輯，但需保留端點、有限誤差與 continuity，否則 typed error。
- GeoJSON `[longitude, latitude]` 在 adapter 轉換，domain／wire 皆使用具名欄位防止倒置。
- 多段 geometry 只在端點連續時合併；禁止跨缺口接直線掩蓋問題。
- Provider duration 不等於使用者自訂速度的模擬時長。

### 5.5 錯誤模型

```json
{
  "requestId": "7fa29c99-6507-4618-94d8-4af824f57e64",
  "error": {
    "code": "GLOBAL_QUOTA_EXHAUSTED",
    "retryAfterSeconds": 3600
  }
}
```

| HTTP | Code | Client behavior |
|---|---|---|
| 400 | `INVALID_REQUEST` | 修正輸入，不重試 |
| 413 | `REQUEST_TOO_LARGE` | 顯示輸入限制 |
| 422 | `NO_ROUTE`, `ROUTE_TOO_COMPLEX`, `ROUTE_OUT_OF_SCOPE` | 不以舊路線掩蓋有效負面結果 |
| 429 | `CLIENT_RATE_LIMITED`, `GLOBAL_QUOTA_EXHAUSTED` | 使用 retry-after 顯示等待；不輪詢 |
| 503 | `SERVICE_DISABLED`, `BUDGET_UNAVAILABLE`, `PROVIDER_UNAVAILABLE` | 本機降級，明確重試 |
| 502 | `INVALID_PROVIDER_RESPONSE` | 不顯示 provider 原始錯誤或 key |
| 504 | `PROVIDER_TIMEOUT` | 不自動重試；保留可能已計費的預算 |

有意義時同時回傳 HTTP `Retry-After`。Worker 平台在額度耗盡時可能回傳非 JSON；Android 必須有 generic unavailable fallback。錯誤訊息由 App 字串資源本地化，不能把未知 server text 直接展示給使用者。

## 6. 全域成本控制與免費層可用性

### 6.1 持久化准入

每個 environment 建立一個具名 `GeoBudget` SQLite Durable Object，並為 production Geoapify key 使用獨立 project／account usage 邊界。若同一額度仍被其他 key、控制台測試或產品共用，必須納入同一預算，否則禁止宣稱嚴格上限。

初始營運配置：

| Setting | Initial value | Meaning |
|---|---|---|
| `GEO_CREDIT_BUDGET_24H` | 2700 | 在已確認 3000 credits 額度內保留 300 安全餘裕 |
| `GEO_GLOBAL_MIN_INTERVAL_MS` | 300 | 保守限制供應商總 dispatch rate |
| `CLIENT_SEARCH_LIMIT_24H` | 30 | 每個 install 的 admitted remote searches |
| `CLIENT_ROUTE_LIMIT_24H` | 10 | 每個 install 的 admitted route attempts，包含縮小迭代 |
| `CLIENT_MIN_INTERVAL_MS` | 2000 | 合併 search/route 的基本防誤觸 |
| `IP_REQUEST_LIMIT_PER_MINUTE` | 120 | 寬鬆濫用限制，避免一般 NAT 過度封鎖 |
| `PROVIDER_TIMEOUT_MS` | 10000 | 單次地理 fetch deadline |
| `MAX_IN_FLIGHT_GEO` | 4 | bounded upstream concurrency |

上述是 App 營運預設，可經後端配置調整，不是 provider 對外承諾。IP／install 均可被輪替；公平性只是 best effort，攻擊仍可能耗盡免費可用性。不得因此啟用付費 WAF 或其他付費產品。

使用 rolling 24h 而非單純本地日曆 reset。精確儲存每次預留的 server timestamp 與 credits；過期紀錄按有界批次刪除。計費時間窗口尚未確認時，TTL 必須額外涵蓋 fetch deadline／clock margin，跨日請求不得在供應商計入的窗口前被釋放。

### 6.2 預留順序與故障語意

1. Worker 驗證 schema、body bounds、allowlist、feature flag。
2. 在 DO 單一 transaction 中檢查 client/global budgets、in-flight 與最早可 dispatch 時間，建立 durable reservation。
3. 不讓 transaction 跨越外部 fetch；DO 作為固定供應商 dispatch authority，避免 Worker 拿到 permit 後任意延遲形成 burst。
4. 立即發出已准入 fetch，設定 deadline。若沒有可立即使用的 slot，回 429，不建立無界佇列或長時間睡眠。
5. 記錄完成狀態；交易與 operation ID 使 finalize 冪等。DO 的同步區段／transaction 要防止 await interleaving 超賣。
6. timeout、取消、重啟、解析錯誤、未知 provider failure 一律保留預留 credits，直到預算窗口過期。
7. 僅「可證明尚未 dispatch」或「供應商有可驗證明確未計費契約」才可釋放預留。不能根據 HTTP error 就假設免費。
8. DO／SQLite 失效、資料遺失、初始化不明或預算配置缺失時拒絕外部 fetch。初次啟用空 budget store 必須由 operator 確認此計費窗口無舊用量；state migration 不得清空帳本。
9. request ID 重複：24h 內拒絕重複 dispatch；同 ID 不同 payload 回 409。只保存 HMAC digest，不保存 query／coordinates。回應丟失時由使用者明確重試新 ID，不能假裝 exactly-once external execution。

### 6.3 路由成本的 launch gate：B01

搜尋一般一次一 credit；路由依中繼點、距離與 options 計費 [S1, S5]。目前文件的部分公式／例子不足以直接作為所有路線的可靠最大費用保證。

**禁止使用 `waypoints.size - 1` 或直線距離當成完整路由成本上界。** 請求完成後才知道距離，不能事後拒絕就當成沒有發生成本。

正式啟用 `routingEnabled` 前，必須完成以下任一條：

- 供應商提供能阻止超額的 account/project hard cap，且多 key／console usage、失敗／超時、跨日及 in-flight 計費均有確認；或
- 取得適用方案的正式最大路線距離／點數／計費契約，推導並測試單次 worst-case reservation，依此在 dispatch 前准入。

實作介面：`estimateMaximumCredits(validatedRequest, billingPolicy): CreditUpperBound | Unsupported`。`Unsupported` 一律拒絕送出；`billingPolicy` 必須有版本與查核證據。不能填猜測常數假裝有硬保證。

若 B01 尚未解決，adapter、fixture 與 UI 可以實作完成，但 production road routing 維持關閉。**目前完整功能發布計畫將 B01 視為阻擋上架的 gate；不自行把關閉道路規劃當成使用者已接受的縮減版。** 這不阻止其他功能及測試環境工作。

### 6.4 快取

- App search cache：記憶體 LRU，最多 50 entries，TTL 5 分鐘；key = normalized query + language + exact bias + provider revision。
- Route cache：保留目前 32 entries / 5 分鐘 fresh TTL；key = API contract revision + provider revision + mode + ordered coordinates + options。
- 最大 stale age 24 小時，僅同 key 的暫時 network／quota unavailable 可使用，且顯示快取時間與警示。
- `NO_ROUTE`、不合法輸入、provider revision 不符不允許 stale fallback。
- 所有 TTL 使用可測試 clock，處理負 age；記憶體資料不跨重啟。
- 不加共享 edge cache 保存地址與路線。已儲存路線、匯出 GPX 的長期保存權利與 attribution 必須完成供應商條款 gate B02；不可因為技術可保存就假設授權允許。

## 7. 永久去廣告與購買權益

### 7.1 Play 設定

- Product ID: `remove_ads_lifetime`，one-time non-consumable，quantity = 1；第一版不做優惠／租用／多購買選項。
- 使用 Play Billing 的當期受支援版本，依賴版本鎖在 version catalog；不得憑本文件日期固定過期版本。
- 支援 Pending purchases；只在 `PURCHASED` 後授予正式權益。
- 不呼叫 consume；成功交付後 acknowledge，必須在 Play 規定期限內完成，目前為 PURCHASED 後三天 [S6]。
- 設定 license testers、測試付款、pending／cancel／refund cases；正式價由 Play 本地化。

### 7.2 State machine

| State | Ads | Purchase UI |
|---|---|---|
| `Unknown` | 不載入，避免付費者啟動畫面閃廣告 | 顯示確認狀態 |
| `Free` | 符合 consent 與 visible 條件才允許 | 可購買 |
| `PurchasePending` | 暫停廣告請求 | 顯示待付款，不當成已永久購買 |
| `VerificationPending` | 暫停廣告請求 | 可重試驗證，禁止重買 |
| `OwnedVerified` | 不初始化／請求廣告 | 顯示已移除、恢復入口 |
| `OwnedOffline` | 維持無廣告 | 說明最近驗證狀態，不催促重買 |
| `Revoked` | 清除權益，依 consent 回到免費廣告 | 顯示權益已撤銷與支援入口 |

網路錯誤不是 `Revoked`，無法查詢不是 `Free`。冷啟動沒有 receipt 且 Play 不可用時維持 Unknown／no ads；這是接受的收入損失，不能用逾時自動傷害付費者。

### 7.3 購買流程

1. Activity visible 時載入 ProductDetails，使用者明確點擊後 launchBillingFlow。
2. listener 收到 PURCHASED token，立即抑制廣告並提交 gateway 驗證；token 只走 TLS body。
3. Worker 以固定 package name、product allowlist 向 Google Play Developer API 查詢，不能信任 caller 的價格／product ownership flags。
4. 驗證 package-bound lookup、product、purchase state、quantity、非租用／非已消耗及 acknowledgement state；測試購買只在受控測試 rollout 允許。
5. 以 purchase token HMAC 作 Purchase DO identity，持久化已驗證 grant，再執行 acknowledgement；ack 必須冪等。
6. 回傳 signed entitlement receipt。Ack 暫時失敗時仍可回傳已驗證 grant + `acknowledgementPending`，但後端必須 durable retry，不依賴 App 留在前景。
7. 確認成功後銷毀現有 AdView、取消排程、更新全 App 權益。

不要在永久權益上只存可自行修改的 `isPremium=true`。Android 使用獨立 EntitlementStore，將 token／receipt 加密保存於 app-private storage，金鑰用 Android Keystore；不得加入 Room 收藏備份／JSON export。

### 7.4 `POST /v1/purchases/verify`

Request fields: `purchaseToken`, `installId`。Product/package 由 server allowlist 固定，不接受 client 控制。

Response fields: `status` (`owned`, `pending`, `revoked`), `receipt` (owned only), `acknowledgementPending`, `serverTime`。

- Receipt 採標準 ES256 JWS，claims 包含 `iss`, `aud`, `productId`, `installId`, `verifiedAt`, `receiptVersion`；header 含 `kid`。
- 不在 receipt 放 raw purchase token、email、Google account ID 或地理資料。
- 使用標準、受維護的驗簽實作；App pin receipt verification public keys，server private key 放 secret。
- Receipt 是本機離線權益證明，不是能取得無限 API 的 bearer authorization。
- Install binding 防止直接複製 receipt；它不能證明 Google 帳號身分或防止已偷取 purchase token 的重放。不宣稱無帳號架構能完全防盜版。
- 支援同 Play 帳號多裝置 restore，不能把 token 永久鎖到第一台 installId。
- Provider 401/403、quota、timeout、5xx、未知 token error 不可直接判為 revoked；回 typed verification unavailable，保留舊 grant。
- 只有 Google authoritative state 確認取消／撤銷，才回 revoked。

Purchase endpoint 有獨立 limiter／budget，不共用地理 credits。每 install 5 次／分鐘、30 次／24h 初始上限，明確 restore 可在 cooldown 後再試；相同 token 的查詢合併，成功 state cache 最長 15 分鐘。日常有效權益背景刷新最多每 24h 一次，manual restore 可要求 fresh lookup。

### 7.5 Ack 重試與 restore

- 新購買驗證成功後，若尚未 ack，Purchase DO encrypted token durable 保存以便重試；持久 state = token hash、encrypted token、product、grant state、ack status、timestamps、attempt count。
- 初始重試間隔 1m、5m、30m、2h、6h，之後最多每 6h，直到成功或 PURCHASED 後 48h 告警。遵守 provider retry-after，never acknowledge pending purchase。
- Token encryption key 放 Worker secrets；DO alarm／fetch／儲存各自算免費額度，ack traffic 與 geo traffic 分離觀測。
- 若 durable grant/retry state 無法保存，驗證 endpoint 不回正常完成；App 保留 provisional no-ads 狀態並重試。Free 平台全面不可用可能仍錯過 3 天而被 Play 自動退款，不能承諾零故障。
- Android Billing connection 建立、回前景及 manual restore 時 queryPurchasesAsync(INAPP)，處理 PROCESS_DEATH、ITEM_ALREADY_OWNED、多 token、pending → purchased。
- 重裝／換機第一次 restore 需要 Play 與 gateway 可用；不要求使用者建立 BloomWalk 帳號。
- 已驗證永久 receipt 不設權益到期日；離線維持無廣告。恢復網路後重新查詢，確認 revoked 即失效。這接受永久離線裝置可能延後撤銷，不承諾即時退款同步。
- Google Play account switch：只有成功、完整的 INAPP 查詢才可判斷目前帳號沒有此商品；再次 fresh query 確認後清除本機「目前帳號權益」，不能撤銷其他裝置的 server grant。失敗／不完整查詢不得清除。
- 第一版不接 Pub/Sub RTDN；撤銷依前景 refresh／restore 的 authoritative lookup，文件與客服不得宣稱即時撤銷。
- Receipt signing key rotation 保留舊 verification keys。輪替計畫先隨 App 發送新公鑰，再切 server key；不能用未知 key 把既有使用者變免費。

## 8. AdMob 與隱私同意

### 8.1 廣告位置與載入條件

- 第一版最多一個 anchored adaptive banner，位於 map 操作區以外的獨立版位；不能覆蓋地圖 attribution、導航、Start/Stop、搖桿。
- 小螢幕、橫向、鍵盤／dialog 顯示、觸控安全距離不足時不顯示廣告，不以壓縮核心控制尺寸換曝光。
- 搖桿正在操作、服務 Starting/Stopping、billing flow 或 setup guide 開啟時暫停該版位。
- 只有 Activity RESUMED + Free entitlement + `adsEnabled` + UMP `canRequestAds()` + 合格版位，才建立廣告請求。
- 不能以 Compose recomposition、多次 consent callback 或 Activity recreation 重複 load。
- 遵守 SDK 自動刷新；不建立自製高頻刷新。onPause/onResume/onDestroy 正確轉送 AdView lifecycle，付費／離開頁面銷毀。
- no-fill、timeout、ad blocker、拒絕適用同意皆不鎖核心功能；不要求觀看廣告才能 Stop。
- 不在 background service、notification、lock screen 顯示廣告，不觸發背景廣告請求。

### 8.2 UMP

- 每次啟動按官方流程 requestConsentInfoUpdate，再視需要顯示 consent form；不能只用自己保存的 bool。
- `canRequestAds()` 是廣告請求 gate；需要時提供 privacy options 入口並能重新開啟。
- 不將「拒絕個人化」直接等同「可以任意請求非個人化廣告」；遵守 UMP／SDK 的實際狀態。
- 付費者不啟動廣告請求或無必要的 monetization SDK 工作；隱私頁仍可查看與管理適用選項。
- Debug、instrumentation、CI 使用官方 test IDs／test devices。開發與驗證不點擊真實廣告。
- release IDs 非 secret，但必須 valid；supplier API key、Play service account、receipt signing key 都不能進 APK。

### 8.3 資料揭露

不得主動把真實或模擬位置、搜尋字串、路線、收藏、購買 token 傳給廣告 SDK。此限制不代表 SDK 沒有自行處理 IP、裝置識別與互動資料；按實際鎖定版本的官方 disclosure 更新 Data Safety [S8–S9]。

既有「沒有廣告 SDK、沒有後端」文案必須移除。正式隱私政策涵蓋：Google/AdMob/UMP、Cloudflare、Geoapify、OpenFreeMap、Google Play，以及資料用途、保存、刪除、聯絡方式。

### 8.4 公開網站與 app-ads.txt

- 準備靜態的繁中／英文 privacy、support 與 App 說明頁，不需要另建 CMS 或付費網域。
- 優先使用擁有者可控的既有免費靜態網站；若沒有，建立免費靜態 hosting，依實際方案確認 root 路徑與免費容量。
- 在 Play 商店的 developer website 所對應主機根目錄發布 `/app-ads.txt`，使用 AdMob Console 提供的真實 publisher 記錄，不能把 ad unit ID 當 publisher ID。
- 確認 HTTPS 可讀、Content-Type 正確、沒有登入／robots 阻擋，並在 AdMob Console 檢查 crawler／app readiness 結果 [S18]。
- 靜態網站與 API 可使用不同主機。不要假設把檔案放在 repo 子路徑就能被廣告 crawler 找到。
- 本階段只產出可部署資產；修改其他網站 repo 或公開部署另依使用者授權執行。

## 9. 保存、記錄與安全

| Data | Location | Retention / rule |
|---|---|---|
| Favorites / saved routes / preferences | Device | 保持既有刪除／備份能力，直到使用者刪除或清除 App |
| Search / route result cache | Device memory | 有界 TTL，不進系統備份 |
| Query / route request payload | Gateway + provider transit | Gateway 不持久化、不寫 application logs；第三方政策另行揭露 |
| Quota records / request digest | GeoBudget DO | 24h + 安全 margin；清理最遲 48h，保留 rolling window 正確性 |
| Pseudonymous rate keys | DO | install ID 與 edge-observed IP 以帶日期 HMAC 處理；只留需要的短窗口，原始 IP 不持久化 |
| Purchase grant / encrypted token | Purchase DO | Active grant 期間；ack 成功後 7 天清除 encrypted token；grant 可由 Play token 重新驗證重建 |
| Revoked purchase diagnostic record | Purchase DO | 最多 30 天，之後刪除；不把它當法定財務帳本 |
| Local entitlement receipt / token | Device private encrypted store | 直到撤銷、clear data 或 uninstall |
| Aggregate operational counters | Backend | 最多 30 天，無精確位置、query、raw token、raw IP |

- 平台 access logs／供應商保存不等於我們的 application logs，正式文件應逐項說明，不能聲稱所有第三方「零保存」。
- Wrangler / exceptions 不得記錄 Authorization、request bodies、包含 apiKey 的 upstream URL。
- 不提供通用 proxy：upstream hostname/path/options 固定 allowlist，禁止 SSRF 與任意 redirect。
- 只信任 Cloudflare 在正式入口提供的 client IP header，不能相信 caller 自訂 X-Forwarded-For。
- 無 App 內共同秘密能防止 APK 提取；不把 CORS、User-Agent 或 installId 稱為安全認證。
- 身分驗證／App attestation 可在後續有濫用證據時評估；第一版不預設 Play Integrity 一定支援此 mock-location 用途或額度。
- 支援聯絡途徑處理隱私／購買問題，不要求使用者寄 raw token。沒有 App 帳號，因此不新增沒有作用的「刪除帳號」功能。

## 10. 設定、環境與發布安全

### 10.1 環境

| Environment | Geo provider | Ads | Billing |
|---|---|---|---|
| Unit / instrumentation | Fake fixtures | Fake / official test IDs | Fake |
| Local gateway | Stub by default | Not applicable | Stub Google responses |
| Staging / Play internal | Small capped live sample | Test ads/test devices | Play license testers |
| Production | Free account with verified gates | Production IDs | Live product |

Staging/prod keys、DO namespaces、receipt keys、logs 分離。若方案把多 project 額度合併，不能視為獨立免費容量；禁止開多帳號規避供應商限制。

### 10.2 配置清單

Public Android build config: `GATEWAY_BASE_URL`, `ADMOB_APP_ID`, `ADMOB_BANNER_UNIT_ID`, `BILLING_PRODUCT_ID`, `PRIVACY_POLICY_URL`, `SUPPORT_URL`, receipt public keys。

Worker secrets: `GEOAPIFY_API_KEY`, `PLAY_SERVICE_ACCOUNT_EMAIL`, `PLAY_SERVICE_ACCOUNT_PRIVATE_KEY`, `RECEIPT_SIGNING_PRIVATE_KEY`, `PURCHASE_TOKEN_ENCRYPTION_KEY`, `RATE_KEY_HMAC_SECRET`。

Server non-secret config: package/product allowlists、feature flags、quota limits、billing policy revision、provider mapping、receipt `kid`。

Google service account 只授予所需的 Play purchase lookup／ack 權限，不能使用 owner credentials。OAuth access token 在記憶體依到期時間短暫快取，不能記錄或回傳給 App。

Release task 必須拒絕 `.invalid` URL、空設定、test ad IDs、debug cleartext、debug signing，以及缺失必要 release secrets 的正式包工作；一般 CI unsigned build 仍可獨立執行並明確標示不能上傳。

### 10.3 供應商切換

固定 Android gateway hostname，provider adapter 在 server 切換；不需要下載可執行碼或讓 App 接受任意服務 URL。每次切換必須：

1. 同一 contract fixtures 通過。
2. 更新 provider revision、attribution、terms/privacy inventory。
3. 舊 route cache 不當成新 provider 結果。
4. 供應商改變造成資料用途變化時完成所需 disclosure／consent，再啟用；「技術可無更新切換」不等於可以悄悄改變資料處理。
5. 無合法／可負擔替代者時關閉線上功能，保留本機功能；不恢復公共 demo fallback。

## 11. 驗證規格與可接受證據

### 11.1 Android 自動測試

- 輸入連續字元、清除、旋轉、返回頁面：remote call count = 0，直到 explicit submit。
- Button 與 IME 提交各只 dispatch 一次；合法座標零 remote calls。
- A → B → A、延遲回應、取消、locale／bias 改變：stale response 不覆寫。
- 本機與 remote 去重、no result、429、非 JSON 503、timeout、oversized body。
- Route modes、point order、provider revision cache 隔離、有限數字、GeoJSON 座標轉換。
- stale fallback 僅適用已指定錯誤；`NO_ROUTE` 不回舊結果。
- 自動旅途四次上限、quota 中途耗盡保留合法候選、未有候選不製造成功結果。
- `OwnedVerified`/`OwnedOffline` 廣告請求數為 0；Unknown 不閃廣告。
- Pending 不授予 permanent receipt；取消不撤銷既有 grant；重複 callbacks 不重複 ack。
- 網路錯誤保留 grant、authoritative revoke 移除 grant、invalid signature／wrong install／wrong product receipt 拒絕。
- ProductDetails unavailable、ITEM_ALREADY_OWNED、process death、restore、account switch。
- UMP 回呼重複與 recomposition 不重複 load；no-fill 不鎖操作；背景零廣告 load。
- 升級保留收藏、路線與設定，新增 schema migration 有 instrumented coverage。

### 11.2 Gateway 測試

- 使用 Workers runtime/DO SQLite 測試實際 transaction semantics，不只 mock 一個記憶體 mutex。
- 100 個平行 search 在只剩 10 credits 時，最多 10 次 dispatch。
- 不同 operation cost 平行 reservation 不超過 budget；Unsupported route cost 零 dispatch。
- Restart／migration 後已用 budget 不歸零；未知帳本拒絕呼叫 provider。
- timeout、abort、5xx、lost response、重複 request ID 與 finalize 不重複釋放／dispatch。
- rolling window、跨日、時間 margin、staging 共用用量；rate/in-flight limits 有 fake clock tests。
- quota store 故障、Worker limit 類型、provider over-response → unavailable，無公共服務 fallback。
- SSRF、無窮值、嵌套過深、超大 request、unknown modes、negative distance、geometry gaps。
- 真實簽章 round trip、wrong kid、篡改 claims、密鑰輪替。
- Google pending/purchased/revoked/unavailable 狀態、ack idempotency、durable alarm 重啟重試、期限告警。
- Source／artifact secret scan，log capture 確認無 query、coordinates、tokens、apiKey。
- Free CPU/rows/storage limits profiling；超過免費 runtime 可行範圍時先優化／降級，不能偷偷轉 Paid。

### 11.3 實際環境驗收

1. 建立少量已計入預算的台灣繁中、英文與日韓地名測試清單；事先記錄預期位置，至少 20 個 query，top-5 可辨識結果達 90%，失敗逐項記錄，不能只靠 schema 正確。
2. 步行、自行車、駕車至少各 5 條代表路線，包含閉合與中繼點；確認連續 geometry、端點 snapping 可接受，記錄偏差。
3. 比較 gateway reservation 與 provider dashboard 用量，測無路線、timeout、四次自動規劃；先用安全小額度，不能為了測試超額而大量耗用。
4. Google Play internal track 實際完成購買、pending、取消、ack、重裝 restore、第二台裝置 restore、退款／撤銷後刷新。
5. Debug/test ads 驗證 UMP 地區流程、no-fill、paid ad suppression；正式測試裝置設為 test device。
6. API 29/34/36 + Sony 或另一 OEM，用正式 R8 build 完成原有裝置矩陣；廣告／gateway/billing 故障時仍能停止模擬。
7. 檢查正式 AAB native libraries 的 16 KB page-size 相容性，並在對應環境測試。
8. Accessibility：TalkBack、200% 字體、繁中／英文、小螢幕、橫向、鍵盤、system insets；廣告不得擋住 Stop。

每份 evidence 記錄 commit、build variant、versionCode、裝置/API、日期、操作、預期、實際、artifact；區分 Local/Fake、Staging Live、Play Internal、Production。CI pass 不代表 Play 審查或外部服務驗收通過。

## 12. 工作拆分與交付順序

| Phase | Deliverables | Acceptance |
|---|---|---|
| P0: Contracts and feasibility | API fixtures、Geoapify terms/cost evidence、DO Free prototype、owner setup checklist | B01/B02 可判定；不花費付費服務 |
| P1: Explicit search and decoupling | input/submit split、gateway adapters、mode/cache解耦、typed errors | 搜尋不再邊打字邊連網；fake tests pass |
| P2: Gateway and budget | schema validation、SQLite DO reservations、provider adapter、kill switches、runbook | concurrency/restart/failure tests pass；小額 live parity |
| P3: Billing and entitlement | Play integration、server verification/ack retries、signed receipt、restore UI | Play internal 完整購買與退款 matrix |
| P4: Ads and consent | banner、UMP、paid suppression、About/privacy/support | consent/lifecycle/paid tests；test ads only until release |
| P5: Release hardening | 中英文文案、資料揭露、店面素材、device matrix、CI、signed AAB | 所有 launch gates 與 release checklist 有證據 |

每個 phase 只修改需要的檔案，review diff，使用既有 formatter/lint/tests。先交付可測試的功能，不因缺少正式帳號資訊而塞入假成功回應。

建議 Android checks：`./gradlew testDebugUnitTest assembleDebug lintDebug assembleRelease bundleRelease`；instrumentation 在指定 emulator/device 執行 `connectedDebugAndroidTest`。新增 gateway scripts：`npm run format:check`, `npm run lint`, `npm run typecheck`, `npm test`。不得把命令列存在視為已執行通過。

## 13. 上架 gate 與擁有者需提供的資料

| Gate | Required evidence / owner input | Blocks |
|---|---|---|
| B01 | Geoapify 路由成本上界或真正硬 cap；Free 無自動付費確認 | Production road routing / full-feature launch |
| B02 | Geoapify 商業用途、attribution、本機快取、永久 route save/GPX export 權利確認 | Provider production enablement |
| B03 | Cloudflare Free account、DO SQLite namespace、免費資源內實測 | Gateway release |
| B04 | Geoapify API key，透過 secret store 設定 | Live geo validation |
| B05 | Play Console app、active product、base price、銷售地區、license testers | Live purchase launch |
| B06 | Play Developer API enablement、最小權限 service account | Server purchase verification |
| B07 | AdMob app/ad unit、app verification/readiness、網站 app-ads.txt 要求完成 | Real ads |
| B08 | 公開 privacy/support 網頁、support email、同意設定、Data Safety、ads declaration | Store review |
| B09 | FGS/location declaration、示範影片、開發者設定操作說明 | Store review |
| B10 | 正式簽署與 app signing、device matrix、16 KB check、pre-launch report | Release readiness |
| B11 | 適用帳號完成至少 12 位連續 14 天 closed testing，申請 production access | Production rollout |
| B12 | 商店目標對象、內容分級、地區及 AdMob 廣告內容設定相符；是否涉及兒童受眾需如實確認 | Store review / ads settings |

尚未指定的只有正式價格、帳號識別與部署秘密、聯絡網址及實際供應商證據；其餘產品／技術預設已在本規格決定。秘密由擁有者在 secret manager／安全本機設定輸入，不貼入文件或 commit。

## 14. 營運與故障處置

- 開始用少量 Play internal testers，再 closed testing，最後小比例正式 rollout；每步獨立確認，不以文件代替部署授權。
- 使用供應商 dashboard 與匿名 aggregate counters 查看：admitted/rejected requests、reserved credits、timeout、provider errors、DO consumption、verification failures、ack pending age。
- Google Play Console / Android vitals 看 crash/ANR；AdMob 看實際曝光/no-fill/revenue；第一版不額外加 analytics SDK。
- 費用指標分開標示「本地預留」「供應商實際」「未知待核對」，不能用預留 credits 假裝 provider invoice。
- Provider outage：server kill switch；保留 cached/local flows，不換公共端點。
- 免費層將耗盡：先關閉新地理 dispatch；購買驗證優先，但平台硬限額仍可能一起不可用，不能承諾購買端絕對隔離。
- Billing outage：關閉新 purchase offer（不能撤銷已有權益），保留 restore retry、offline grant，追蹤 ack backlog。
- Ad incident：停止 load／server ads kill switch，不能因廣告故障中斷模擬。
- 免費與付費者受相同公平使用政策；不自動把 premium 當 API 優先隊列。
- 發現額度已不足且使用者需求穩定時，只提出調整方案；沒有另行授權不得升級付費服務。
- Rollback 保留 purchase product、grant store、receipt verification keys 與 quota ledger；不能為回復版本刪除永久付費權益。
- 每次供應商/SDK/政策變更重查隱私、授權、限額；Free terms 變更時有權暫停線上服務，商品承諾仍只有移除廣告。

## 15. Definition of Done

- [ ] 所有文字輸入無遠端搜尋，明確 submit 才連網。
- [ ] 正式版不再呼叫公共 Nominatim／FOSSGIS demo。
- [ ] Gateway 固定契約、bounded I/O、allowlist、typed errors 與安全 logs 完成。
- [ ] 精確持久化 budget 通過並行／重啟／跨窗口測試；B01/B02 有正式證據。
- [ ] 路由四次迭代的成本與中途失敗體驗已驗證，既有本機功能保留。
- [ ] 永久去廣告有 Play 實際購買／ack／restore／refund 證據，未知狀態不閃廣告。
- [ ] UMP 正確、廣告不干擾定位控制、付費版零廣告請求。
- [ ] 隱私政策、Data Safety、來源標示、app-ads.txt 與商店內容一致。
- [ ] signed release、裝置測試、16 KB、Play testing gates 完成。
- [ ] Cloudflare/Geoapify 保持免費方案，未啟用自動加購。
- [ ] README / README-tw / ARCHITECTURE / RELEASE_HARDENING / PRIVACY 同步。
- [ ] 交付說明明確區分實作完成、本地驗證、live 驗證與 Play 核准狀態。

## 16. Primary sources

查核日期：2026-09-28。政策／價格／SDK 要求可能變動，實作鎖版與發布時重新核對。來源描述是設計依據，不代表官方保證此 App 必然通過審查。

- [S1 Geoapify pricing and commercial free-plan conditions](https://www.geoapify.com/pricing/)
- [S2 Cloudflare Workers limits](https://developers.cloudflare.com/workers/platform/limits/)
- [S3 Cloudflare Workers pricing](https://developers.cloudflare.com/workers/platform/pricing/)
- [S4 Durable Objects pricing and SQLite Free limits](https://developers.cloudflare.com/durable-objects/platform/pricing/)
- [S5 Geoapify routing API and credit calculation](https://apidocs.geoapify.com/docs/routing/)
- [S6 Google Play Billing integration and acknowledgement](https://developer.android.com/google/play/billing/integrate)
- [S7 Google Play purchase verification security](https://developer.android.com/google/play/billing/security)
- [S8 AdMob UMP Android integration](https://developers.google.com/admob/android/privacy)
- [S9 AdMob Google Play data disclosure](https://developers.google.com/admob/android/privacy/play-data-disclosure)
- [S10 Google Play Payments policy](https://support.google.com/googleplay/android-developer/answer/10281818?hl=en)
- [S11 Google Play User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en)
- [S12 New personal account testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en)
- [S13 Google Play foreground-service declarations](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en)
- [S14 Android 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes)
- [S15 Geoapify terms and conditions](https://www.geoapify.com/terms-and-conditions/)
- [S16 Nominatim public endpoint policy](https://operations.osmfoundation.org/policies/nominatim/)
- [S17 Google Play one-time purchase lookup v2](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.productsv2/getproductpurchasev2)
- [S18 AdMob app-ads.txt setup](https://developers.google.com/admob/android/app-ads)
- [S19 Geoapify geocoding contract](https://apidocs.geoapify.com/docs/geocoding/)

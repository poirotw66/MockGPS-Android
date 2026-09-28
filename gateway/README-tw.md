# BloomWalk GPS gateway

這是 BloomWalk GPS 的 Cloudflare Worker，負責轉接 Geoapify 搜尋／路由與 Google Play 購買驗證。此套件只使用 **Workers Free** 與 SQLite Durable Objects，不啟用付費方案、自動加購，也不會回退到公共 Nominatim／FOSSGIS。

## 現況

- 正式環境功能旗標預設為 **false**：`searchEnabled`、`routingEnabled`、`purchasesEnabled`、`adsEnabled`。
- 本機預設使用供應商 **stub**（`GEO_PROVIDER_MODE=stub`、`PLAY_API_MODE=stub`）。
- 缺少 Geoapify 金鑰時，**不會**偽造成功的地理結果。正式 live 模式沒有金鑰會回 `PROVIDER_UNAVAILABLE`。
- **B01 尚未解決。** 在擁有者完成可驗證、具版本的 `billingPolicy` 之前，`estimateMaximumCredits` 對路由一律回傳 `Unsupported`。正式環境的 `routingEnabled` 必須維持關閉。詳見 [../docs/GEOAPIFY_TERMS_COST_EVIDENCE.md](../docs/GEOAPIFY_TERMS_COST_EVIDENCE.md)。

## 端點

| 方法   | 路徑                   | 說明                                                           |
| ------ | ---------------------- | -------------------------------------------------------------- |
| `GET`  | `/v1/config`           | 公開能力，可短時間快取 5 分鐘。                                |
| `POST` | `/v1/search`           | 僅使用 request body，查詢字串不可出現在 gateway URL。          |
| `POST` | `/v1/route`            | 僅使用 request body。路由費用上界為 `Unsupported` 時拒絕送出。 |
| `POST` | `/v1/purchases/verify` | 獨立限流，不消耗地理 credits。                                 |

地理／購買請求需帶 `X-Install-ID`、`X-Client-Version`、`X-Request-ID`（原樣回傳）。

## 本機 stub

Geoapify stub 只對規格中的「台北車站」fixture 回傳成功結果，其他查詢回空陣列，不會為任意文字捏造地點。Google stub 只對少數測試 token 對應 purchased／pending／revoked／unavailable；未知 token 回 **verification unavailable**，不會當成已擁有，也不會當成已撤銷。

## 指令

```bash
npm install
npm run format:check
npm run lint
npm run typecheck
npm test
npm run dev
```

測試使用 `@cloudflare/vitest-pool-workers` 與實際的 SQLite Durable Object。沒有即時 Cloudflare 帳號時，測試仍應通過。

## 秘密

請複製 `.dev.vars.example` 為本機 `.dev.vars`，不要把真實金鑰提交到 git。秘密名稱見英文 README。

## Durable Objects

- `GeoBudget`：滾動 24 小時 credits 預留、HMAC 限流鍵、in-flight 上限。
- `Purchase`：token HMAC 身分、加密 token、權益／ack 狀態與 alarm 重試。

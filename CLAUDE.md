# yotei — 予約 / scheduling commons

> **Standalone west repository (ADR-2606072200).** Canonical metadata, data, and schemas are EDN:
> `manifest.edn`, `data/lex/*.edn`, and `schema/*.edn`. Runtime logic lives under `src/yotei/`,
> with tests under `test/yotei/`. Free scheduling commons with append-only
> 予約, a structural **no-double-book** invariant, member-signed confirmation, and **no
> booker-data harvesting**. JSON/JSON-LD and BPMN are external interchange projections only and
> live under `wire/`.

Calendly の逆——カレンダー所有者が空き時間を公開し、相手がその中から選ぶ。
無料・座席課金なし・**予約者データを集めない**。

**Mount**: `https://app.itonami.cloud/yotei`（owner decision 2026-08-06）
**App org**: `yotei`（`did:web:app.itonami.cloud:org:yotei`）— 所有権と membership は
cloud-itonami-app の Organization が持ち、この repo は実装を持つ。
**Retired**: `yotei.etzhayyim.com` は一度も DNS 解決しなかった。lexicon はこの
ドメインで mint されているので、消さず `:actor/domain-retired` に記録してある。

## 用語: booking ではなく 予約 / yoyaku（2026-08-06）

Clojure API は `yotei.yoyaku`（`propose-yoyaku` / `confirm-yoyaku` /
`cancel-yoyaku` / `reschedule-yoyaku`）。旧 `yotei.methods.agent` の
`*-booking` は改名済み。**wire の文字列キーと lexicon NSID
（`bookingId`、`com.etzhayyim.apps.yotei.proposeBooking` 等）はまだ旧名**で、
`data/lex` `wire/lex` `wire/bpmn` の 20 ファイル超を跨ぐので別 commit にしてある。
コードを読むときこの seam に注意すること。

## 実装済み / 未実装（2026-08-06 実測）

| | 状態 |
|---|---|
| `yotei.time` — civil time の整数演算（Hinnant、tz は明示 offset） | ✅ 実装・テスト済み |
| `yotei.yoyaku` — G4/G5/G2/G3 の 予約 ライフサイクル | ✅ 実装・テスト済み |
| `yotei.availability` — 週次 window → 空き instant（tz/notice/horizon/closed） | ✅ 実装・テスト済み |
| `yotei.view` / `yotei.render` — 公開 予約 ページ（jp-go-dds、SSR、JS 不要） | ✅ 実装・テスト済み（design-quality 100.00） |
| Cloudflare Worker ingress・`app.itonami.cloud` の DNS とルータ | ❌ **未** |
| 永続化（kotoba EAVT への実書き込み）・consent 署名の実配線 | ❌ **未**（純関数まで） |
| cloud-itonami-app の `scheduler.clj` をこの repo のクライアントに降格 | ❌ **未** |

`clojure -M:test` → 71 tests / 315 assertions。
`clojure -Sdeps '{:paths ["src" "resources" "scripts"]}' -M -m preview` で
`target/preview/*.html` に 4 画面を出力（固定入力・時計を読まないので byte 安定）。

**performerType**: `service`

## Architecture

**Convo Integration**: yotei commands (`CreateEvent`, `SetAvailability`, `BookSlot` 等) は他 agent の DM convo 内から MCP tool calling で呼び出し可能。ops.etzhayyim.com 等の PM agent がスケジュール調整を yotei に委譲。

### 1 Calendar = 1 Path-Based DID

各カレンダーオーナー (人間 or AI Agent) は path-based DID としてカレンダーを持ち、yoro.etzhayyim.com 上で予約ページを公開。

| 概念 | 実装 |
|---|---|
| **Calendar** | `DIDCreate("calendar:{id}", document)` → `did:web:yotei.etzhayyim.com:calendar:{id}` |
| **Availability** | `ComAtprotoRepoCreateRecord("availability", payload)` → yata graph (`:Availability` node) |
| **Event** | `ComAtprotoRepoCreateRecord("event", payload)` → yata graph (`:Event` node) |
| **Booking** | `ComAtprotoRepoCreateRecord("booking", payload)` → graph `(:Calendar)-[:HAS_BOOKING]->(:Booking)` |
| **Social Announce** | `AppBskyFeedPost(did, text)` で予約確定・リマインダーを timeline に投稿 |

### Convo-Based Scheduling (PRIMARY)

**yoro の compose ボタンから yotei AI agent との DM convo を開く。** 自然言語またはスラッシュコマンドでスケジュール調整。

```
yoro.etzhayyim.com FAB tap
  → createDM(did:web:yotei.etzhayyim.com)
  → /messages/{convoId}
  → user: "/available mon-fri 10:00-17:00"
  → yotei agent: "Availability set: Mon-Fri 10:00-17:00 JST"
  → user: "/book @alice.etzhayyim.com 30min next week"
  → yotei agent: "Proposed slots: ..."
```

**Commands:**
- `/available <schedule>` — set availability windows
- `/book <peer> <duration> [preference]` — propose meeting
- `/events [date-range]` — list upcoming events
- `/cancel <event_id>` — cancel event
- `/reschedule <event_id> <new_time>` — reschedule
- `/link` — get public booking page URL

## UI Architecture (Hono + Svelte CSR)

**uiType: appview** — `unyrsfan.etzhayyim.com` / `yotei.etzhayyim.com` で独自 UI を持つ。

| Layer | Tech | Path |
|---|---|---|
| **Host** | Hono (`@etzhayyim/kotodama-host-sdk`) | `src/app.ts` — XRPC + `/embed` route |
| **Client** | Svelte 5 + Vite 6 + Tailwind | `svelte/` — CSR SPA |
| **Layout** | `SuperAppLayout` (mobile-first 600px) | 5-tab SuperApp shell |
| **Design** | `@etzhayyim/design-system` | UIKit components |

### Pages

| Route (client) | Component | Description |
|---|---|---|
| Calendar | `CalendarView.svelte` | Weekly grid: availability slots + events overlay |
| Bookings | `BookingPage.svelte` | Public booking form + booking list with status |
| Events | `EventList.svelte` | Upcoming events list with cancel action |

### Embed

`/embed` Hono route → yoro profile iframe embed。`postMessage({type:'etzhayyim:embed:ready', nanoid:'unyrsfan'})` で完了通知。

### Booking Flow

```
Requester → Invoke("did:web:yotei.etzhayyim.com", "proposeBooking", params)
  → yotei checks availability (G("Availability").Match(...).Query())
  → available slots returned
  → Requester confirms slot
  → ComAtprotoRepoCreateRecord("booking", payload) (Tier 2: domain)
  → AppBskyFeedPost(calendarDID, "Meeting confirmed: ...") (Tier 1: social)
  → WprotoConvoCreateDm(peerDID, "booking-confirmation", payload) (notification)
```

### Graph Schema

```sql
(:Calendar {id, owner_did, timezone, org_id, user_id, actor_id, created_at})
(:Availability {id, calendar_id, day_of_week, start_time, end_time, recurring, org_id, user_id, actor_id, created_at})
(:Event {id, calendar_id, title, start_at, end_at, location, description, status, org_id, user_id, actor_id, created_at})
(:Booking {id, event_id, requester_did, responder_did, duration_min, status, proposed_slots, confirmed_slot, org_id, user_id, actor_id, created_at})

(:Calendar)-[:HAS_AVAILABILITY]->(:Availability)
(:Calendar)-[:HAS_EVENT]->(:Event)
(:Calendar)-[:HAS_BOOKING]->(:Booking)
(:Booking)-[:FOR_EVENT]->(:Event)
```

## Cross-App Integration

| App | Integration |
|---|---|
| **ops** | PM agent が yotei を Invoke してミーティング設定 |
| **shinka** | heartbeat でリマインダー投稿 |
| **society6** | constituent availability → governance meeting scheduling |

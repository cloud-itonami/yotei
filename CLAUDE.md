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

## 用語: 予約 / yoyaku（booking は撤去済み、2026-08-06）

Clojure API・wire キー・lexicon NSID のすべてが `yoyaku`。`data/` `wire/`
`manifest.edn` に `booking` は 1 件も残っていない（`scripts/rename-booking-to-yoyaku.cljs`）。
NSID は同時に `com.etzhayyim.*` → `cloud.itonami.*` へ再ホームした
（`cloud.itonami.apps.yotei.proposeYoyaku` / `cloud.itonami.yotei.yoyaku`）。
何も deploy されていない今が唯一の無償で改名できる時点だった。

例外: **`com.etzhayyim.encrypted.*` は残す** — G2 が指す封筒型で、他 actor の
名前空間に属する。自分の prefix が変わったから他人の型を改名するのは、参照を
移すのではなく壊すことになる。

## 実装済み / 未実装（2026-08-06 実測）

| | 状態 |
|---|---|
| `yotei.time` — civil time の整数演算（Hinnant、tz は明示 offset） | ✅ |
| `yotei.yoyaku` — G4/G5/G2/G3 の 予約 ライフサイクル | ✅ |
| `yotei.availability` — 週次 window → 空き instant（tz/notice/horizon/closed） | ✅ |
| `yotei.store` — append-only ログ + CAS。`decide-propose`/`decide-confirm` は純関数 | ✅ |
| `yotei.view` / `yotei.render` — 公開 予約 ページ（jp-go-dds、SSR、JS 不要） | ✅ design-quality 100.00 |
| `yotei.edge.*` — Cloudflare Worker | ✅ **本番稼働中** |
| `yotei.edge.log-do` — Durable Object（正本）+ KV mirror | ✅ lost-update 解消 |
| **`https://app.itonami.cloud/yotei/c/<calendar>`** | ✅ **live** |
| `yotei.schedule` — 招待/RSVP の予定（cloud-itonami-app から統合） | ✅ |
| `scripts/calendar.cljs` — カレンダー作成 CLI（検証してから公開） | ✅ |
| `scripts/e2e_public.cljs` — 実ブラウザで公開ページを操作する harness | ✅ |
| `yotei.envelope` — ECDH P-256 + AES-GCM の封筒、ECDSA 署名 | ✅ |
| `scripts/owner.cljs` — owner の keygen / list（復号）/ confirm（署名） | ✅ |
| owner console（web） | ❌ 未（下記の理由で CLI が先） |

`clojure -M:test` → 98 tests / 393 assertions。
`nbb --classpath src scripts/envelope_test.cljs` → 封筒と署名の 16 検査（WebCrypto は
JVM に無いので JVM suite の外）。

## 連絡先の封筒と、確定の署名（2026-08-07）

**カレンダーは公開鍵を 2 つ持ち、秘密鍵は kagi の `yotei-owner-<segment>` にある。**

- **封筒**: ECDH P-256 →（HKDF-SHA-256）→ AES-256-GCM。ephemeral 鍵を毎回作るので
  1 件の連絡先が漏れても次には効かない。**AAD に yoyakuId を縛る**ので、封筒を別の
  予約 に貼り替えると GCM が拒否する。Worker は封をして平文を忘れる —— yotei は
  秘密鍵を持たない（G5 が署名について言うのと同じ性質）ので、Worker 侵害・KV dump・
  DO の押収、どれも ciphertext しか出さない
- **確定**: owner が `yotei/confirm/v1\n<did>\n<yoyakuId>` に ECDSA 署名し、Worker が
  カレンダーの公開鍵で検証する。**Worker は確認できるが作れない** = G5
- **ページの文面は鍵の有無から導出する**。定数で書いていた時に「暗号化して預かる」と
  嘘をついて公開されたので、文と ciphertext の原因を 1 つにした

```bash
nbb --classpath src scripts/owner.cljs keygen  <segment>            # 鍵生成（公開鍵を出力）
nbb --classpath src scripts/owner.cljs list    <segment>            # 一覧 + 連絡先を復号
nbb --classpath src scripts/owner.cljs confirm <segment> <yoyakuId> # 署名して確定
nbb --classpath src scripts/owner.cljs watch   <segment>            # 新着を待つ（macOS 通知）
```

## 通知（2026-08-07）

**予約 が入っても owner が気づかない**という穴を塞いだ。2 経路あり、どちらも
**個人情報を運ばない**:

- **webhook**（任意）: カレンダーの `:notify-webhook` に metadata を POST。
  `waitUntil` なので遅い/壊れた webhook が 予約 を失敗させない
- **`owner.cljs watch`**: owner の端末で polling し、macOS 通知を出す。**鍵が
  あるのはここだけ**なので、名前と連絡先を復号して表示できる

**名前も連絡先も封筒の中。** 最初は「名前が無いと通知が使えない」と考えて名前を
平文で載せたが、**名前も予約 PII であり G2 に都合のいい半分だけの例外は無い**。
webhook に載せれば、その URL の持ち主（Slack 等）に平文で渡ることになり、2 つ隣の
namespace でやっている暗号化が無意味になる。通知は**いつ**と id だけを運び、
**誰か**は owner の端末で開く。

実測: 通知本文に `山田太郎` も `yamada-secret@example.com` も 0 件、KV にも 0 件、
`owner.cljs list` では両方見える。

⚠ **email 通知は未配線。** secrets-location-map が指す
`op://gftdcojp/gftd.resend/credential` は**その vault に存在しない**（2026-08-07、
1Password は応答したので不在であってタイムアウトではない）。kagi にも Keychain にも
記載名では無い。**map 自身が繰り返し記録している drift の再発**。鍵が出てきたら
`yotei.edge.notify` に分岐を足して `:notify-email` を足すだけ。

**なぜ web console ではなく CLI か。** 確定には秘密鍵が要り（G5）、連絡先を読むには
**復号鍵**が要る。**passkey は署名できても復号できない** —— cloud-itonami-app 自身の
note が「Passkey は Data Integrity proof を作れない」と書いているのと同じ分岐。
browser console にすると owner の生の秘密鍵をブラウザに置くことになり、この repo は
まだその鍵管理の答えを持っていない。web console は作る価値があるが、**鍵の置き場所の
答えが先**。

## 公開中のカレンダー

| リンク | |
|---|---|
| `https://app.itonami.cloud/yotei/c/jun` | 30分の打ち合わせ |
| `https://app.itonami.cloud/yotei/c/jun-15min` | 15分の相談 |
| `https://app.itonami.cloud/yotei/c/jun-review` | 60分の設計レビュー |

正本は `calendars/jun.edn`。**`:windows` は動作確認用の仮の値**なので、実際に
人を招く前に本当の空き時間に直すこと。

```bash
nbb --classpath src scripts/calendar.cljs put calendars/jun.edn --dry-run  # 何枠出るか
nbb --classpath src scripts/calendar.cljs put calendars/jun.edn            # 公開
nbb --classpath src scripts/calendar.cljs list                             # 一覧
nbb scripts/e2e_public.cljs <url>                                          # 実ブラウザ検証
```

**1 エントリ = 1 リンク。** Calendly の単位が人ではなく「用件の種類」なのと同じ。
`:name` を省くと見出しもタブのタイトルも owner 名だけになり、複数リンクを持つと
相手が区別できない。

## 運用

```bash
npx shadow-cljs release worker          # dist/worker.js（dds.css を compile 時に inline）
npx wrangler deploy --dispatch-namespace ai-gftd-repository-dispatch
```

`itonami-fleet-dispatch` が `app.itonami.cloud/yotei/*` を受けて `yotei` を
先頭セグメントとして剥がすので、**この Worker が見るパスは `/c/<calendar>`**。
script 名・repo 名・`:app/mount` の 3 つは同じ文字列であることで一致している
（対応表は無い）。

カレンダーは KV の `calendar:<did>` に EDN で置く。

## 永続化: Durable Object が正本、KV は projection（2026-08-07 解決）

**KV の lost-update は実測で確認され、Durable Object で塞いだ。**

計測（`scripts/concurrency_probe.cljs`）: 1 カレンダーに 8 件の 予約 を同時投入
（全部別の枠なので全部通るのが正しい）。

| | 受理 | 保存 | 失われた |
|---|---|---|---|
| KV（read-modify-write + version 検査） | 8 | 2 | **6（75%）** |
| Durable Object | 10 | 10 | 0 |
| Durable Object（16 同時） | 16 | 16 | 0 |

6 人が「申し込みを受け付けました」と言われて、他人の書き込みに黙って消された。
version 検査は窓を狭めるだけで、KV に atomic CAS が無い以上閉じない。

**DO は カレンダー DID ごとに `idFromName`。**グローバルに一意で単一スレッドなので、
「書き手はちょうど1人」が*実装するもの*ではなく*コードが動く場所の性質*になる
（lease も fencing epoch も retry ループも要らない）。

- **正本は `ctx.storage`**（強整合・トランザクショナル）
- **KV は mirror**。`yoyaku-log:<did>` を消しても何も失われない — 次の書き込みで
  DO が再構築する（**実測で確認: 16 件のログを消して、1 件 propose したら 17 件で
  復活**）。CLAUDE.md の「消して再構築できるなら cache」テストに合格する
- mirror を await しない。append は既に durable で、遅い KV 書き込みが応答を
  遅らせる理由が無い
- **DO は規則を持たない。**判断は `yotei.store/decide-propose` /
  `decide-confirm`（JVM store と同じ純関数）。DO が足すのは直列化だけ

## 運用 — テストデータの消去

`POST /yotei/admin/clear/<segment>` + `x-yotei-admin: <token>`。**owner console では
ない** — 確定/取消は member 署名（G5）の後ろで owner view に置く。これは deploy
資格情報を持つ人の運用ツールで、このセッションが公開ページ経由で作った ~35 件の
テスト 予約 を消すために作った（DO は正しく、ただ頼んだだけの相手には返さない）。

token は **kagi `personal/yotei-admin-token`**。namespaced worker には
`wrangler secret put` が届かない（`--dispatch-namespace` フラグが無い）。
**`wrangler@4.119` 以降の `deploy --secrets-file` が通る経路**で、同梱の 4.69 には
このフラグが無い:

```bash
printf '{"YOTEI_ADMIN_TOKEN":"%s"}' "$(kagi get yotei-admin-token)" > /tmp/s.json
npx wrangler@4.119.0 deploy --dispatch-namespace ai-gftd-repository-dispatch --secrets-file /tmp/s.json
rm /tmp/s.json
```

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

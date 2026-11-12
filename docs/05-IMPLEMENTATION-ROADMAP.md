# 05 — Phased Implementation Roadmap

## Phase 1: Product & Architecture Documentation (Current)
- [x] Finalize product vision & 1-app-1-account V1 scope.
- [x] Audit `earn-share-mobile-app/IG-REQ-RES/` engagement & discovery captures.
- [x] Publish `docs/01` through `docs/05` in `ig-grow-app/docs/`.

---

## Phase 2: Cloud AI & Planning Backend (`ig-grow-api`)
1. Scaffold Node.js + TypeScript service (`ig-grow-app/backend/`).
2. Integrate Firebase Admin Auth middleware (reusing pattern from `Instant-Peer-APIs`).
3. Implement Gemini AI Growth Planner service (`@google/genai`) with structured JSON schema for 7-day plans & comment templates.
4. Implement Firestore/D1 persistence and REST endpoints:
   - `POST /v1/account/register`
   - `POST /v1/plan/generate`
   - `POST /v1/plan/approve`
   - `GET /v1/tasks/daily`
   - `POST /v1/telemetry/batch`
5. Add unit tests for plan schema validation and safety ceiling clamping.

---

## Phase 3: Android Application (`ig-grow-android`)
1. Scaffold Kotlin + Jetpack Compose Android project (`ig-grow-app/android/`).
2. Port & simplify WebView login + `CookieManager` + `fb_dtsg`/`lsd` extraction from `earn-share-mobile-app` (1 account, no Ktor server, no tunneling).
3. Implement `EncryptedSharedPreferences` + Room DB (`ActionQueueEntity`, `InteractionHistoryEntity`, `DailyStatsEntity`).
4. Implement `IgGraphqlExecutor` (OkHttp) covering:
   - Discovery: `search`, `search_tag`, `similar_accounts`, `about`, `posts`, `post_likers`, `story`.
   - Engagements: `follow`, `hit_like`, `hit_comment`, `view_story`, `like_view_story`.
5. Implement `SafetyEngine` (warm-up tiers, jitter, active hours, deduplication, 429/challenge circuit breaker).
6. Implement `WorkManager` + `ForegroundService` background scheduler.
7. Build Compose UI: WebView Login → Goal/Niche Wizard → AI Plan Review/Approval → Live Growth Dashboard.

---

## Phase 4: Pilot Testing & Phase 2 Feature Prep
1. Run 14-day internal pilot on real test accounts starting at Tier 1.
2. Monitor challenge rates, `doc_id` stability, and follower/engagement lift; tune Gemini prompts and jitter curves.
3. Prepare Phase 2 backlog:
   - Capture IG web upload endpoints for **Post/Reel/Story Creation**.
   - Add **Unfollow** & **Save/Repost** workflows.
   - Add **Multi-Account Isolation** (if agency demand warrants) and **SaaS Billing** (Stripe / Google Play).

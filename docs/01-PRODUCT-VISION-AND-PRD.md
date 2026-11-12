# 01 — Product Vision & PRD: `ig-grow-app`

## 1. Executive Summary

**`ig-grow-app`** is an AI-driven, on-device Instagram organic growth application for **Android (Kotlin + Jetpack Compose)**. Designed for creators, brands, and social media managers (1 app install = 1 Instagram account in V1), it combines **Cloud AI growth planning (Gemini)** with **residential-IP background execution** on the user's own mobile device.

By reusing the proven WebView session interception and background execution architecture from `earn-share-mobile-app` (without the Ktor server or tunneling), `ig-grow-app` avoids datacenter-IP bot detection and executes engagements (`follow`, `like`, `comment`, `story view`, `story like`) at human-like intervals under a **Strict Safety Engine**.

---

## 2. Problem Statement

Existing Instagram growth tools fail in two ways:
1. **Cloud Bots & Proxies**: Running automation from datacenter servers or shared proxies triggers Instagram's `challenge_required`, `429`, and shadowban defenses.
2. **Dumb Static Rules**: Traditional bots spam fixed hashtags or generic comments ("Nice pic!") at fixed intervals, hurting account reputation and failing to adapt to real performance.

**`ig-grow-app` solves both**:
- **Device-Native Execution**: Every Instagram API call runs from the user's Android phone over their real mobile/Wi-Fi IP using their authenticated WebView session.
- **Hybrid AI Growth Planner**: Cloud AI (Gemini) analyzes the account's niche, competitors, and goals to generate a **7-day adaptive plan** + contextual comment templates. The user reviews/edits once, activates, and the AI adapts daily based on results.

---

## 3. Target User (V1)

- **Primary**: Solo creators, influencers, small businesses, and social media managers running **1 dedicated Instagram account per Android device**.
- **Scope Note**: Multi-account switching on a single device is intentionally deferred to Phase 2 to keep V1 simple, eliminate cookie-jar conflicts, and preserve a strict 1:1 device-to-account safety posture.

---

## 4. Core User Journey

1. **Connect Account (WebView Login)**
   - User opens `ig-grow-app` and signs into Instagram via the embedded Android `WebView`.
   - The app intercepts and securely stores session cookies (`sessionid`, `csrftoken`, `ds_user_id`, `mid`, `ig_did`) and dynamic web tokens (`fb_dtsg`, `lsd`, `x-ig-app-id`, `x-asbd-id`).
2. **Goal & Niche Onboarding**
   - User selects a primary goal:
     - *Increase Followers*
     - *Boost Post/Reel Engagement*
     - *Niche Authority & Story Visibility*
   - User provides: niche/industry, target keywords/hashtags, 3–5 competitor/benchmark accounts, preferred comment tone/language, and active hours.
3. **AI Plan Review (Hybrid Approval)**
   - Cloud AI (Gemini) generates a **7-day Growth Plan** containing:
     - Daily action budgets (respecting account warm-up tier).
     - Target discovery strategy (competitor likers/followers, hashtag posts, similar accounts).
     - Sample contextual comment templates for approval/editing.
   - User reviews, tweaks if desired, and taps **Activate Growth Plan**.
4. **Background Autopilot & Live Dashboard**
   - `WorkManager` + `ForegroundService` executes queued discovery & engagement tasks periodically in the background.
   - Dashboard shows:
     - Status badge (`Active`, `Warming Up`, `Sleeping (Outside Active Hours)`, `Paused - Challenge`).
     - Today's executed vs. budgeted actions (Likes, Comments, Story Views, Story Likes, Follows).
     - Follower/engagement growth trend and 1-tap **Pause / Resume** control.

---

## 5. V1 MVP vs. Phase 2 Scope

| Capability | V1 MVP | Phase 2 |
| :--- | :--- | :--- |
| **Account Model** | 1 App = 1 IG Account | Multi-account switching & isolated profiles |
| **Auth & Session** | Android WebView + `CookieManager` + Encrypted Store | Silent multi-profile token rotation |
| **AI Planner** | Gemini 7-day plan + Hybrid user approval + daily adaptation | Real-time per-post multimodal vision comments |
| **Discovery** | `search`, `search_tag`, `similar_accounts`, `post_likers`, `posts`, `reels`, `story`, `about` | Advanced audience scoring & lookalike clustering |
| **Engagements** | Follow, Like, Comment, Story View, Story Like | Unfollow cleanup, Save/Repost/DM workflows |
| **Content Creation** | Out of scope | AI Post/Reel/Story captioning & scheduled upload |
| **Monetization** | Free / Internal tool first | SaaS tiers (Free / Pro / Agency) via Stripe/Play Billing |

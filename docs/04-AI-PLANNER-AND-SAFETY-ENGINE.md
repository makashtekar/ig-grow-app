# 04 — AI Growth Planner & Strict Safety Engine

## 1. Hybrid AI Growth Planner (Gemini API)

### 1.1 Inputs to Gemini
1. **Account Snapshot**: Username, bio, follower/following ratio, post count, estimated account age/tier.
2. **User Goal & Niche**: Primary goal (*Increase Followers*, *Boost Engagement*, *Niche Authority*), niche keywords, 3–5 competitor handles, target hashtags, comment tone/language.
3. **24h Telemetry (for Daily Adaptation)**: Executed actions, success/error rates, follower delta.

### 1.2 Structured Output Schema
Gemini outputs a strict JSON **7-Day Growth Plan**:
- `strategy_summary`: Human-readable explanation of why these targets & actions were chosen.
- `daily_schedules[1..7]`:
  - `discovery_sources`: Ranked list of competitor handles (to target their `post_likers` / `posts`) and hashtags (`search_tag`).
  - `action_budgets`: Capped counts for `story_view`, `story_like`, `post_like`, `comment`, `follow`.
  - `comment_templates`: 10–15 niche-specific, natural comment templates (no generic spam) that the user can edit/delete before activation.

### 1.3 Hybrid Approval Flow
1. User reviews the 7-day plan and comment templates in the Android app.
2. User can adjust daily limits downward (never above the Safety Engine's hard ceiling) and edit/remove comment templates.
3. User taps **Activate** → Plan is locked in and synced to the phone for execution.

---

## 2. Strict Safety Engine (Anti-Ban Architecture)

All safety limits are enforced **on-device** in `ig-grow-android` regardless of what the Cloud AI suggests.

### 2.1 Account-Age Warm-Up Tiers
| Tier | Account Profile | Story Views / Day | Story Likes / Day | Post Likes / Day | Comments / Day | Follows / Day |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Tier 1 (Cold / New)** | < 30 days or new to automation | 10–20 | 3–5 | 8–15 | 2–3 | 0–5 |
| **Tier 2 (Warming)** | 7+ days clean execution | 25–40 | 8–12 | 20–35 | 5–8 | 8–15 |
| **Tier 3 (Established)** | 21+ days clean execution | 40–70 | 12–20 | 35–60 | 8–15 | 15–25 |

> **Hard Rule**: Any `429` or `feedback_required` automatically demotes the account down one tier after a mandatory cooldown.

### 2.2 Humanization & Pacing
- **Active Hours Window**: Actions only execute during the user's configured waking hours (default `09:00` – `22:00` local time).
- **Randomized Jitter**: Inter-action delays use randomized intervals (e.g., 45s–180s between engagements, with occasional 15–45 minute "coffee breaks" between mini-sessions).
- **Read-Before-Write**: Before liking or commenting on a post, or following a user, the executor fetches the target's profile (`about`) or media (`posts`) first.
- **Deduplication**: Room DB tracks every `target_user_id` and `media_id` interacted with so the app never likes/comments/follows the same target twice within a rolling 30-day window.

### 2.3 Automatic Circuit Breaker
The executor **immediately halts** background automation and sends a local notification to the user if any of the following occur:
1. HTTP `429` (Rate Limited) → Auto-pause for 24 hours.
2. `challenge_required` / `checkpoint_required` → Hard pause until user resolves the challenge in the in-app WebView and manually taps **Resume**.
3. `login_required` / `sessionid` invalidated → Hard pause until user re-authenticates via WebView.
4. 3 consecutive GraphQL failures → Pause for 2 hours and log diagnostic telemetry.

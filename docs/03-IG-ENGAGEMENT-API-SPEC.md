# 03 — Instagram Engagement & Discovery API Specification

All endpoints below are derived directly from verified captures in `earn-share-mobile-app/IG-REQ-RES/`.

## 1. Common Request Requirements

Every Instagram Web GraphQL call targets:
- **URL**: `POST https://www.instagram.com/api/graphql`
- **Content-Type**: `application/x-www-form-urlencoded`

### Required Headers
| Header | Source / Value |
| :--- | :--- |
| `cookie` | `sessionid`, `csrftoken`, `ds_user_id`, `mid`, `ig_did`, `datr`, `rur` |
| `x-csrftoken` | Matches `csrftoken` cookie |
| `x-ig-app-id` | `936619743392459` (or `1217981644879628` mobile web) |
| `x-asbd-id` | `359341` |
| `x-fb-lsd` | Extracted `lsd` token from WebView session |
| `x-fb-friendly-name` | GraphQL operation name (see table below) |
| `origin` / `referer` | `https://www.instagram.com` (and target page URL) |
| `user-agent` | Device WebView User-Agent |

### Required Form Body Fields
- `av`: Actor/IG ID (`178414...` or `ds_user_id`)
- `fb_dtsg`: Extracted `fb_dtsg` token
- `lsd`: Matches `x-fb-lsd`
- `jazoest`: Numeric checksum derived from `fb_dtsg`
- `fb_api_caller_class`: `RelayModern`
- `fb_api_req_friendly_name`: Operation friendly name
- `doc_id`: GraphQL persisted query ID
- `variables`: URL-encoded JSON payload

---

## 2. V1 Engagement Endpoints (Verified from `IG-REQ-RES/`)

| Action | Source File | `fb_api_req_friendly_name` | `doc_id` | Key `variables` |
| :--- | :--- | :--- | :--- | :--- |
| **Follow User** | `follow.md` | `usePolarisFollowMutation` | `26508036048874888` | `target_user_id`, `container_module: "profile"` |
| **Like Post/Reel** | `hit_like.md` | `usePolarisLikeMediaXIGLikeMutation` | `27182485238052618` | `input: { actor_id, client_mutation_id, container_module, media_id, tracking_token }` |
| **Comment on Post** | `hit_comment.md` | `PolarisPostCommentInputRevampedMutation` | `29086430320952860` | `data: { comment_text, media_id }`, `root_connections` |
| **View Story** | `view_story.md` | `PolarisStoriesV3SeenMutation` | `26234228992942885` | `reelId`, `reelMediaId`, `reelMediaOwnerId`, `reelMediaTakenAt`, `viewSeenAt` |
| **Like Story** | `like_view_story.md` | `usePolarisStoriesV4LikeMutationLikeMutation` | `26938887309082050` | `input: { actor_id, client_mutation_id, media_id }` |

### Secondary / Captured Endpoints (Available for Expansion)
| Action | Source File | `fb_api_req_friendly_name` | `doc_id` | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **Repost Media** | `repost_media.md` | `usePolarisCreateMediaRepostMutation` | `27933176649688558` | `input: { actor_id, media_id, audience, note_style, text }` |
| **Share Media (DM)** | `share_media.md` | `IGDirectMediaShareMutation` | `29164221929874709` | `send_data: { recipient_users }`, `data: { media_id }` |
| **Add to Favourites** | `add_to_favourites.md` | `usePolarisUpdateFeedFavoritesUpdatableFavoriteMutation` | `27984384297894481` | `data: { add: [userId], remove: [], source }` |
| **Save Media (Picker)** | `save_media.md` | `PolarisSavedCollectionPickerQuery` | `29115677831365401` | *Note*: Captures collection picker; actual save mutation `doc_id` to be captured during Phase 3. |
| **Share Story View** | `share_view_story.md` | *(empty file)* | — | Pending capture if needed in Phase 2. |

---

## 3. Discovery Endpoints (Used Before Engagement)

Used by the on-device executor to find relevant targets and perform natural "read-before-write" browsing:
- `search.md` & `search_tag.md` — Keyword & hashtag discovery.
- `similar_accounts.md` — Competitor lookalike discovery.
- `about.md` — Target profile metadata, follower/following counts, account type.
- `posts.md`, `posts_paginated.md`, `reels.md`, `reels_paginated.md` — Fetch recent media IDs & `tracking_token`s.
- `post_likers.md` & `post_comments.md` — Find active users engaging with competitor posts.
- `story.md` — Fetch active story `reelId` / `reelMediaId` / `reelMediaTakenAt` for Story View & Story Like.

---

## 4. Error & Challenge Detection

Every GraphQL response must be inspected for:
- HTTP `429` (Too Many Requests) → **Trigger immediate cooldown / circuit breaker**.
- HTTP `302` redirect to `/accounts/login/` or `/challenge/` → **Pause automation & notify user**.
- JSON body containing `"require_login": true`, `"challenge"`, `"feedback_required"`, or `"spam": true` → **Pause automation & surface status on Dashboard**.

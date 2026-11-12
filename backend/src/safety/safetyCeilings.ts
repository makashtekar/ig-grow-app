import type {
  AccountStatus,
  DailyActionBudget,
  TelemetryEvent,
  WarmupTier,
} from "../types/growth.js";

/**
 * Hard daily ceilings per WarmupTier from docs/04-AI-PLANNER-AND-SAFETY-ENGINE.md.
 * The AI planner and user edits can never exceed these bounds.
 */
export const TIER_CEILINGS: Record<WarmupTier, DailyActionBudget> = {
  TIER_1_COLD: {
    storyViews: 20,
    storyLikes: 5,
    postLikes: 15,
    comments: 3,
    follows: 5,
  },
  TIER_2_WARMING: {
    storyViews: 40,
    storyLikes: 12,
    postLikes: 35,
    comments: 8,
    follows: 15,
  },
  TIER_3_ESTABLISHED: {
    storyViews: 70,
    storyLikes: 20,
    postLikes: 60,
    comments: 15,
    follows: 25,
  },
};

/**
 * Determine initial warm-up tier for a newly registered account.
 * Even established accounts start at TIER_1_COLD for automation safety unless
 * explicitly promoted through clean execution days.
 */
export function initialTierForAccount(accountAgeDays: number): WarmupTier {
  if (accountAgeDays < 30) {
    return "TIER_1_COLD";
  }
  return "TIER_1_COLD";
}

/**
 * Clamp any AI-generated or user-edited daily action budget to the hard tier ceiling.
 */
export function clampBudgetToTier(
  budget: DailyActionBudget,
  tier: WarmupTier
): DailyActionBudget {
  const ceiling = TIER_CEILINGS[tier];
  return {
    storyViews: Math.max(0, Math.min(budget.storyViews, ceiling.storyViews)),
    storyLikes: Math.max(0, Math.min(budget.storyLikes, ceiling.storyLikes)),
    postLikes: Math.max(0, Math.min(budget.postLikes, ceiling.postLikes)),
    comments: Math.max(0, Math.min(budget.comments, ceiling.comments)),
    follows: Math.max(0, Math.min(budget.follows, ceiling.follows)),
  };
}

export function demoteTier(tier: WarmupTier): WarmupTier {
  if (tier === "TIER_3_ESTABLISHED") return "TIER_2_WARMING";
  return "TIER_1_COLD";
}

export function promoteTierIfEligible(
  tier: WarmupTier,
  cleanDaysStreak: number
): WarmupTier {
  if (tier === "TIER_1_COLD" && cleanDaysStreak >= 7) {
    return "TIER_2_WARMING";
  }
  if (tier === "TIER_2_WARMING" && cleanDaysStreak >= 21) {
    return "TIER_3_ESTABLISHED";
  }
  return tier;
}

export interface SafetyEvaluationResult {
  nextStatus: AccountStatus;
  nextTier: WarmupTier;
  nextCleanDaysStreak: number;
  pausedUntil: string | null;
  reason: string | null;
}

/**
 * Evaluate a batch of telemetry events against circuit-breaker rules.
 */
export function evaluateTelemetrySafety(
  currentStatus: AccountStatus,
  currentTier: WarmupTier,
  cleanDaysStreak: number,
  events: TelemetryEvent[],
  now: Date = new Date()
): SafetyEvaluationResult {
  const hasLoginRequired = events.some((e) => e.outcome === "LOGIN_REQUIRED");
  if (hasLoginRequired) {
    return {
      nextStatus: "PAUSED_LOGIN_REQUIRED",
      nextTier: currentTier,
      nextCleanDaysStreak: 0,
      pausedUntil: null,
      reason: "Instagram session expired or login required; re-authenticate in WebView.",
    };
  }

  const hasChallenge = events.some((e) => e.outcome === "CHALLENGE_REQUIRED");
  if (hasChallenge) {
    return {
      nextStatus: "PAUSED_CHALLENGE",
      nextTier: demoteTier(currentTier),
      nextCleanDaysStreak: 0,
      pausedUntil: null,
      reason: "Instagram challenge/checkpoint detected; resolve in WebView before resuming.",
    };
  }

  const hasRateLimit = events.some((e) => e.outcome === "RATE_LIMITED_429");
  if (hasRateLimit) {
    const resumeAt = new Date(now.getTime() + 24 * 60 * 60 * 1000).toISOString();
    return {
      nextStatus: "PAUSED_RATE_LIMIT",
      nextTier: demoteTier(currentTier),
      nextCleanDaysStreak: 0,
      pausedUntil: resumeAt,
      reason: "HTTP 429 rate limit detected; account demoted and paused for 24h.",
    };
  }

  const nextStatus: AccountStatus =
    currentTier === "TIER_1_COLD" ? "WARMING_UP" : currentStatus === "PAUSED_USER" ? "PAUSED_USER" : "ACTIVE";

  return {
    nextStatus,
    nextTier: promoteTierIfEligible(currentTier, cleanDaysStreak),
    nextCleanDaysStreak: cleanDaysStreak,
    pausedUntil: null,
    reason: null,
  };
}

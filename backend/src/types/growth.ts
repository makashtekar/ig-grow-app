import { z } from "zod";

export const WarmupTierSchema = z.enum([
  "TIER_1_COLD",
  "TIER_2_WARMING",
  "TIER_3_ESTABLISHED",
]);
export type WarmupTier = z.infer<typeof WarmupTierSchema>;

export const GrowthGoalSchema = z.enum([
  "INCREASE_FOLLOWERS",
  "BOOST_ENGAGEMENT",
  "NICHE_AUTHORITY",
]);
export type GrowthGoal = z.infer<typeof GrowthGoalSchema>;

export const AccountStatusSchema = z.enum([
  "ACTIVE",
  "WARMING_UP",
  "PAUSED_USER",
  "PAUSED_RATE_LIMIT",
  "PAUSED_CHALLENGE",
  "PAUSED_LOGIN_REQUIRED",
]);
export type AccountStatus = z.infer<typeof AccountStatusSchema>;

export const DailyActionBudgetSchema = z.object({
  storyViews: z.number().int().min(0),
  storyLikes: z.number().int().min(0),
  postLikes: z.number().int().min(0),
  comments: z.number().int().min(0),
  follows: z.number().int().min(0),
});
export type DailyActionBudget = z.infer<typeof DailyActionBudgetSchema>;

export const DiscoverySourceSchema = z.object({
  type: z.enum(["COMPETITOR_LIKERS", "COMPETITOR_POSTS", "HASHTAG", "SIMILAR_ACCOUNTS"]),
  target: z.string().min(1),
  priority: z.number().int().min(1).max(10),
});
export type DiscoverySource = z.infer<typeof DiscoverySourceSchema>;

export const DayPlanSchema = z.object({
  dayNumber: z.number().int().min(1).max(7),
  focus: z.string().min(1),
  discoverySources: z.array(DiscoverySourceSchema).min(1),
  actionBudgets: DailyActionBudgetSchema,
});
export type DayPlan = z.infer<typeof DayPlanSchema>;

export const GrowthPlanSchema = z.object({
  planId: z.string().min(1),
  dsUserId: z.string().min(1),
  ownerUid: z.string().min(1),
  status: z.enum(["DRAFT", "APPROVED", "SUPERSEDED"]),
  strategySummary: z.string().min(1),
  dailySchedules: z.array(DayPlanSchema).length(7),
  commentTemplates: z.array(z.string().min(1)).min(1),
  createdAt: z.string(),
  approvedAt: z.string().nullable(),
});
export type GrowthPlan = z.infer<typeof GrowthPlanSchema>;

export const RegisterAccountInputSchema = z.object({
  dsUserId: z.string().min(1),
  username: z.string().min(1),
  followersCount: z.number().int().min(0).default(0),
  followingCount: z.number().int().min(0).default(0),
  postsCount: z.number().int().min(0).default(0),
  accountAgeDays: z.number().int().min(0).default(0),
  goal: GrowthGoalSchema,
  niche: z.string().min(2),
  competitorHandles: z.array(z.string().min(1)).min(1).max(10),
  targetHashtags: z.array(z.string().min(1)).min(1).max(15),
  commentTone: z.string().min(2).default("friendly, authentic, concise"),
  activeHoursStart: z.number().int().min(0).max(23).default(9),
  activeHoursEnd: z.number().int().min(0).max(23).default(22),
  timezone: z.string().min(1).default("UTC"),
});
export type RegisterAccountInput = z.infer<typeof RegisterAccountInputSchema>;

export interface IgAccountRecord extends RegisterAccountInput {
  ownerUid: string;
  tier: WarmupTier;
  status: AccountStatus;
  cleanDaysStreak: number;
  pausedUntil: string | null;
  activePlanId: string | null;
  updatedAt: string;
}

export const ApprovePlanInputSchema = z.object({
  dsUserId: z.string().min(1),
  planId: z.string().min(1),
  commentTemplates: z.array(z.string().min(1)).min(1).optional(),
  dailySchedules: z.array(DayPlanSchema).length(7).optional(),
});
export type ApprovePlanInput = z.infer<typeof ApprovePlanInputSchema>;

export const TelemetryEventSchema = z.object({
  actionType: z.enum([
    "STORY_VIEW",
    "STORY_LIKE",
    "POST_LIKE",
    "COMMENT",
    "FOLLOW",
    "DISCOVERY",
  ]),
  outcome: z.enum([
    "SUCCESS",
    "RATE_LIMITED_429",
    "CHALLENGE_REQUIRED",
    "LOGIN_REQUIRED",
    "FAILED",
  ]),
  targetId: z.string().optional(),
  httpStatus: z.number().int().optional(),
  errorMessage: z.string().optional(),
  executedAt: z.string(),
});
export type TelemetryEvent = z.infer<typeof TelemetryEventSchema>;

export const TelemetryBatchInputSchema = z.object({
  dsUserId: z.string().min(1),
  followersCount: z.number().int().min(0).optional(),
  events: z.array(TelemetryEventSchema).min(1),
});
export type TelemetryBatchInput = z.infer<typeof TelemetryBatchInputSchema>;

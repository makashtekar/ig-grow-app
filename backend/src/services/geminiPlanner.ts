import { GoogleGenAI, Type } from "@google/genai";
import { clampBudgetToTier, TIER_CEILINGS } from "../safety/safetyCeilings.js";
import type { DayPlan, IgAccountRecord } from "../types/growth.js";

export interface GeneratedPlanPayload {
  strategySummary: string;
  dailySchedules: DayPlan[];
  commentTemplates: string[];
}

export interface SuggestedTargetsPayload {
  competitorHandles: string[];
  targetHashtags: string[];
  recommendedTone: string;
  nicheAdvice: string;
}

export interface PlannerService {
  generateSevenDayPlan(account: IgAccountRecord): Promise<GeneratedPlanPayload>;
  suggestGrowthTargets(niche: string, goal?: string): Promise<SuggestedTargetsPayload>;
  generateContextualComment(postCaption: string, niche?: string, tone?: string): Promise<string>;
}

const SUGGESTED_TARGETS_SCHEMA = {
  type: Type.OBJECT,
  properties: {
    competitorHandles: {
      type: Type.ARRAY,
      items: { type: Type.STRING },
      description: "4-6 popular, active Instagram creator handles in this niche without @ prefix.",
    },
    targetHashtags: {
      type: Type.ARRAY,
      items: { type: Type.STRING },
      description: "6-8 specific, high-intent hashtags for this niche without # prefix.",
    },
    recommendedTone: {
      type: Type.STRING,
      description: "Recommended comment tone, e.g. 'friendly, authentic, concise'.",
    },
    nicheAdvice: {
      type: Type.STRING,
      description: "1-2 sentences of actionable strategic advice for growing in this niche.",
    },
  },
  required: ["competitorHandles", "targetHashtags", "recommendedTone", "nicheAdvice"],
};

const PLAN_JSON_SCHEMA = {
  type: Type.OBJECT,
  properties: {
    strategySummary: {
      type: Type.STRING,
      description: "Concise 2-3 sentence explanation of the 7-day organic growth strategy.",
    },
    commentTemplates: {
      type: Type.ARRAY,
      items: { type: Type.STRING },
      description: "10-15 authentic, niche-relevant comment templates without generic spam.",
    },
    dailySchedules: {
      type: Type.ARRAY,
      items: {
        type: Type.OBJECT,
        properties: {
          dayNumber: { type: Type.INTEGER },
          focus: { type: Type.STRING },
          discoverySources: {
            type: Type.ARRAY,
            items: {
              type: Type.OBJECT,
              properties: {
                type: {
                  type: Type.STRING,
                  enum: [
                    "COMPETITOR_LIKERS",
                    "COMPETITOR_POSTS",
                    "HASHTAG",
                    "SIMILAR_ACCOUNTS",
                  ],
                },
                target: { type: Type.STRING },
                priority: { type: Type.INTEGER },
              },
              required: ["type", "target", "priority"],
            },
          },
          actionBudgets: {
            type: Type.OBJECT,
            properties: {
              storyViews: { type: Type.INTEGER },
              storyLikes: { type: Type.INTEGER },
              postLikes: { type: Type.INTEGER },
              comments: { type: Type.INTEGER },
              follows: { type: Type.INTEGER },
            },
            required: ["storyViews", "storyLikes", "postLikes", "comments", "follows"],
          },
        },
        required: ["dayNumber", "focus", "discoverySources", "actionBudgets"],
      },
    },
  },
  required: ["strategySummary", "commentTemplates", "dailySchedules"],
};

export class GeminiGrowthPlanner implements PlannerService {
  private readonly ai: GoogleGenAI;
  private readonly model: string;
  private readonly fallback = new HeuristicFallbackPlanner();

  constructor(apiKey: string, model = process.env.GEMINI_MODEL || "gemini-3-flash-preview") {
    this.ai = new GoogleGenAI({ apiKey });
    this.model = model;
  }

  async generateSevenDayPlan(account: IgAccountRecord): Promise<GeneratedPlanPayload> {
    const ceiling = TIER_CEILINGS[account.tier];
    const prompt = [
      `You are an Instagram organic growth strategist.`,
      `Create a safe, realistic 7-day engagement plan for:`,
      `- Username: @${account.username}`,
      `- Niche: ${account.niche}`,
      `- Primary Goal: ${account.goal}`,
      `- Followers: ${account.followersCount}, Following: ${account.followingCount}, Posts: ${account.postsCount}`,
      `- Safety Tier: ${account.tier}`,
      `- Hard Daily Ceilings (NEVER exceed): storyViews=${ceiling.storyViews}, storyLikes=${ceiling.storyLikes}, postLikes=${ceiling.postLikes}, comments=${ceiling.comments}, follows=${ceiling.follows}`,
      `- Competitor Accounts: ${account.competitorHandles.join(", ")}`,
      `- Target Hashtags: ${account.targetHashtags.join(", ")}`,
      `- Comment Tone: ${account.commentTone}`,
      `Return exactly 7 dailySchedules (dayNumber 1..7) and 10-12 natural commentTemplates.`,
    ].join("\n");

    try {
      const response = await this.ai.models.generateContent({
        model: this.model,
        contents: prompt,
        config: {
          responseMimeType: "application/json",
          responseJsonSchema: PLAN_JSON_SCHEMA,
        },
      });

      if (!response.text) {
        throw new Error("Gemini returned an empty response when generating growth plan.");
      }

      const parsed = JSON.parse(response.text) as GeneratedPlanPayload;
      return clampGeneratedPlan(parsed, account);
    } catch (err) {
      console.warn("Gemini plan generation failed; falling back to heuristic planner:", err);
      return this.fallback.generateSevenDayPlan(account);
    }
  }

  async suggestGrowthTargets(niche: string, goal = "INCREASE_FOLLOWERS"): Promise<SuggestedTargetsPayload> {
    const prompt = [
      `You are an Instagram organic growth strategist.`,
      `Suggest target accounts and hashtags for an Instagram creator with:`,
      `- Niche: ${niche}`,
      `- Primary Goal: ${goal}`,
      `Provide 4-6 real, active, high-quality creator handles in this niche (without @), 6-8 relevant hashtags (without #), an authentic tone, and 1-2 sentences of actionable niche advice.`,
    ].join("\n");

    try {
      const response = await this.ai.models.generateContent({
        model: this.model,
        contents: prompt,
        config: {
          responseMimeType: "application/json",
          responseJsonSchema: SUGGESTED_TARGETS_SCHEMA,
        },
      });

      if (!response.text) {
        throw new Error("Gemini returned empty response for target suggestion.");
      }
      return JSON.parse(response.text) as SuggestedTargetsPayload;
    } catch (err) {
      console.warn("Gemini target suggestion failed (spike/503); falling back to heuristic:", err);
      return this.fallback.suggestGrowthTargets(niche, goal);
    }
  }

  async generateContextualComment(postCaption: string, niche = "lifestyle", tone = "friendly, authentic, concise"): Promise<string> {
    const prompt = [
      `You are a genuine Instagram user in the '${niche}' niche.`,
      `The creator's post caption is: "${postCaption}".`,
      `Write one natural, engaging 1-sentence comment that specifically references the context or subject mentioned in the caption.`,
      `Tone: ${tone}.`,
      `Rules:`,
      `- Strictly 1 sentence.`,
      `- Do not use generic filler like "great post", "nice pic", "love this photo".`,
      `- Do not use hashtags or emojis in excess (max 1 natural emoji).`,
      `- Sound like a real person having a conversation.`,
    ].join("\n");

    try {
      const response = await this.ai.models.generateContent({
        model: this.model,
        contents: prompt,
      });

      const text = response.text?.trim()?.replace(/^["']|["']$/g, "");
      return text || this.fallback.generateContextualComment(postCaption, niche, tone);
    } catch (err) {
      console.warn("Gemini contextual comment generation failed; falling back to heuristic:", err);
      return this.fallback.generateContextualComment(postCaption, niche, tone);
    }
  }
}

/**
 * Deterministic fallback planner used in local dev/tests when GEMINI_API_KEY is absent.
 */
export class HeuristicFallbackPlanner implements PlannerService {
  async suggestGrowthTargets(niche: string, goal = "INCREASE_FOLLOWERS"): Promise<SuggestedTargetsPayload> {
    const cleanNiche = niche.toLowerCase().trim();
    if (cleanNiche.includes("nature") || cleanNiche.includes("travel")) {
      return {
        competitorHandles: ["earthpix", "wilderness_culture", "discoverearth", "nature"],
        targetHashtags: ["naturephotography", "travelgram", "wanderlust", "earthfocus", "roamtheplanet"],
        recommendedTone: "friendly, authentic, concise",
        nicheAdvice: "Focus on leaving thoughtful comments on top nature creators within 30 minutes of their post going live to capture active engagement.",
      };
    }
    if (cleanNiche.includes("fitness") || cleanNiche.includes("gym") || cleanNiche.includes("workout")) {
      return {
        competitorHandles: ["gym.tips", "fitnessmotivation", "bodybuilding.daily", "workoutvideos"],
        targetHashtags: ["fitnessmotivation", "gymlife", "workoutroutine", "fitfam", "healthylifestyle"],
        recommendedTone: "motivational, encouraging, concise",
        nicheAdvice: "Engage with creators sharing workout routines and answer questions in their comment section to establish authority.",
      };
    }
    const token = cleanNiche.replace(/[^a-z0-9]/g, "");
    return {
      competitorHandles: [`${token}.daily`, `${token}creators`, `best.${token}`, `${token}community`],
      targetHashtags: [token, `${token}life`, `${token}love`, "explore", "community"],
      recommendedTone: "friendly, authentic, concise",
      nicheAdvice: `Focus on engaging with recent likers of top ${niche} creators during morning and evening peak hours.`,
    };
  }

  async generateContextualComment(postCaption: string, niche = "lifestyle", tone = "friendly, authentic, concise"): Promise<string> {
    if (postCaption.toLowerCase().includes("view") || postCaption.toLowerCase().includes("sunset")) {
      return "The lighting and perspective in this shot are stunning!";
    }
    return `Such a thoughtful perspective on this — thanks for sharing!`;
  }

  async generateSevenDayPlan(account: IgAccountRecord): Promise<GeneratedPlanPayload> {
    const ceiling = TIER_CEILINGS[account.tier];
    const primaryCompetitor = account.competitorHandles[0] ?? account.username;
    const primaryHashtag = account.targetHashtags[0] ?? account.niche;

    const dailySchedules: DayPlan[] = Array.from({ length: 7 }, (_, idx) => {
      const dayNumber = idx + 1;
      const rampFactor = 0.6 + idx * 0.06;
      return {
        dayNumber,
        focus:
          dayNumber <= 2
            ? `Warm-up discovery around @${primaryCompetitor} and #${primaryHashtag}`
            : `Targeted ${account.goal.toLowerCase().replace("_", " ")} in ${account.niche}`,
        discoverySources: [
          { type: "COMPETITOR_LIKERS", target: primaryCompetitor, priority: 9 },
          { type: "HASHTAG", target: primaryHashtag, priority: 7 },
        ],
        actionBudgets: clampBudgetToTier(
          {
            storyViews: Math.round(ceiling.storyViews * rampFactor),
            storyLikes: Math.round(ceiling.storyLikes * rampFactor),
            postLikes: Math.round(ceiling.postLikes * rampFactor),
            comments: Math.round(ceiling.comments * rampFactor),
            follows: Math.round(ceiling.follows * rampFactor),
          },
          account.tier
        ),
      };
    });

    return {
      strategySummary: `Gradual 7-day ${account.tier} organic plan for @${account.username} in ${account.niche}, focusing on @${primaryCompetitor} likers and #${primaryHashtag}.`,
      dailySchedules,
      commentTemplates: [
        `Really love how you broke this down for ${account.niche}!`,
        `This perspective is super refreshing — saving this for later.`,
        `Great attention to detail here!`,
        `Totally agree with this take on ${account.niche}.`,
        `Clean execution — keep these coming!`,
      ],
    };
  }
}

export function clampGeneratedPlan(
  plan: GeneratedPlanPayload,
  account: IgAccountRecord
): GeneratedPlanPayload {
  return {
    strategySummary: plan.strategySummary,
    commentTemplates: plan.commentTemplates,
    dailySchedules: plan.dailySchedules.map((day) => ({
      ...day,
      actionBudgets: clampBudgetToTier(day.actionBudgets, account.tier),
    })),
  };
}

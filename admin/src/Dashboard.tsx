import { useEffect, useState } from "react";
import { adminFetch, clearAuthToken } from "./api";
import {
  Activity,
  AlertTriangle,
  Bot,
  Calendar,
  CheckCircle2,
  Clock,
  Layers,
  LogOut,
  Pause,
  Play,
  RefreshCw,
  Sliders,
  Sparkles,
  Users,
} from "lucide-react";

export function Dashboard() {
  const [activeTab, setActiveTab] = useState<"overview" | "accounts" | "plans" | "logs" | "settings">("overview");
  const [overview, setOverview] = useState<any>(null);
  const [accounts, setAccounts] = useState<any[]>([]);
  const [plans, setPlans] = useState<any[]>([]);
  const [logs, setLogs] = useState<any[]>([]);
  const [settings, setSettings] = useState<any>(null);
  const [loading, setLoading] = useState(false);
  const [actionMessage, setActionMessage] = useState<string | null>(null);

  const loadData = async () => {
    setLoading(true);
    try {
      const [ovRes, accRes, plRes, logRes, setRes] = await Promise.all([
        adminFetch("/admin/overview"),
        adminFetch("/admin/accounts"),
        adminFetch("/admin/plans"),
        adminFetch("/admin/logs?limit=50"),
        adminFetch("/admin/settings"),
      ]);

      if (ovRes.ok) setOverview(await ovRes.json());
      if (accRes.ok) setAccounts((await accRes.json()).accounts || []);
      if (plRes.ok) setPlans((await plRes.json()).plans || []);
      if (logRes.ok) setLogs((await logRes.json()).logs || []);
      if (setRes.ok) setSettings((await setRes.json()).settings || null);
    } catch (err: any) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, []);

  const handleAccountStatus = async (dsUserId: string, newStatus: string) => {
    try {
      const res = await adminFetch(`/admin/accounts/${dsUserId}`, {
        method: "PATCH",
        body: JSON.stringify({ status: newStatus, clearCooldown: newStatus === "ACTIVE" }),
      });
      if (res.ok) {
        setActionMessage(`Updated ${dsUserId} to ${newStatus}`);
        loadData();
      }
    } catch (err: any) {
      console.error(err);
    }
  };

  const handleAccountTier = async (dsUserId: string, newTier: string) => {
    try {
      const res = await adminFetch(`/admin/accounts/${dsUserId}`, {
        method: "PATCH",
        body: JSON.stringify({ tier: newTier }),
      });
      if (res.ok) {
        setActionMessage(`Updated tier for ${dsUserId} to ${newTier}`);
        loadData();
      }
    } catch (err: any) {
      console.error(err);
    }
  };

  return (
    <div className="min-h-screen flex flex-col bg-obsidian text-gray-100">
      {/* Top Navbar */}
      <header className="h-16 border-b border-outlineSubtle bg-surfaceCard px-6 flex items-center justify-between sticky top-0 z-20">
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-xl bg-gradient-to-tr from-coral via-magenta to-amberBrand flex items-center justify-center shadow-lg">
            <Sparkles className="w-5 h-5 text-white" />
          </div>
          <div>
            <h1 className="font-bold text-white text-base tracking-wide flex items-center gap-2">
              IG Grow Studio <span className="text-[10px] uppercase font-bold tracking-widest px-2 py-0.5 rounded-full bg-magenta/20 text-magenta border border-magenta/40">Admin</span>
            </h1>
          </div>
        </div>

        <div className="flex items-center gap-4">
          <button
            onClick={loadData}
            disabled={loading}
            className="flex items-center gap-1.5 text-xs text-gray-300 hover:text-white bg-surfaceElevated border border-outlineSubtle px-3 py-1.5 rounded-lg transition"
          >
            <RefreshCw className={`w-3.5 h-3.5 ${loading ? "animate-spin" : ""}`} />
            Refresh
          </button>
          <button
            onClick={() => {
              clearAuthToken();
              window.location.reload();
            }}
            className="flex items-center gap-1.5 text-xs text-roseStatus/90 hover:text-roseStatus bg-roseStatus/10 border border-roseStatus/30 px-3 py-1.5 rounded-lg transition"
          >
            <LogOut className="w-3.5 h-3.5" />
            Sign Out
          </button>
        </div>
      </header>

      {/* Main Container */}
      <div className="flex-1 flex max-w-7xl w-full mx-auto p-6 gap-6">
        {/* Sidebar Nav */}
        <nav className="w-60 flex flex-col gap-1.5 shrink-0">
          <NavItem
            icon={<Activity className="w-4 h-4" />}
            label="Overview"
            active={activeTab === "overview"}
            onClick={() => setActiveTab("overview")}
          />
          <NavItem
            icon={<Users className="w-4 h-4" />}
            label="IG Accounts"
            badge={accounts.length.toString()}
            active={activeTab === "accounts"}
            onClick={() => setActiveTab("accounts")}
          />
          <NavItem
            icon={<Calendar className="w-4 h-4" />}
            label="Growth Plans"
            badge={plans.length.toString()}
            active={activeTab === "plans"}
            onClick={() => setActiveTab("plans")}
          />
          <NavItem
            icon={<Clock className="w-4 h-4" />}
            label="Action Logs"
            active={activeTab === "logs"}
            onClick={() => setActiveTab("logs")}
          />
          <NavItem
            icon={<Sliders className="w-4 h-4" />}
            label="Safety Settings"
            active={activeTab === "settings"}
            onClick={() => setActiveTab("settings")}
          />
        </nav>

        {/* Content Pane */}
        <main className="flex-1 min-w-0">
          {actionMessage && (
            <div className="mb-4 p-3 rounded-xl bg-emeraldStatus/10 border border-emeraldStatus/40 text-emeraldStatus text-xs flex justify-between items-center">
              <span>{actionMessage}</span>
              <button onClick={() => setActionMessage(null)} className="hover:underline">Dismiss</button>
            </div>
          )}

          {activeTab === "overview" && (
            <div className="space-y-6">
              {/* KPI Cards */}
              <div className="grid grid-cols-4 gap-4">
                <KpiCard
                  title="Total IG Accounts"
                  value={overview?.totalAccounts ?? 0}
                  icon={<Users className="w-5 h-5 text-amberBrand" />}
                />
                <KpiCard
                  title="Active / Warming"
                  value={`${(overview?.activeAccounts ?? 0) + (overview?.warmingAccounts ?? 0)}`}
                  icon={<Bot className="w-5 h-5 text-emeraldStatus" />}
                />
                <KpiCard
                  title="Paused / Challenges"
                  value={overview?.pausedAccounts ?? 0}
                  icon={<AlertTriangle className="w-5 h-5 text-roseStatus" />}
                />
                <KpiCard
                  title="Total Plans Generated"
                  value={overview?.totalPlans ?? 0}
                  icon={<Layers className="w-5 h-5 text-magenta" />}
                />
              </div>

              {/* Recent Accounts Preview */}
              <div className="bg-surfaceCard border border-outlineSubtle rounded-2xl p-5">
                <h3 className="font-semibold text-white text-sm mb-3">Recently Registered Accounts</h3>
                <div className="divide-y divide-outlineSubtle/60">
                  {accounts.slice(0, 5).map((acc) => (
                    <div key={acc.dsUserId} className="py-2.5 flex items-center justify-between text-xs">
                      <div>
                        <span className="font-semibold text-white">@{acc.username}</span>
                        <span className="text-gray-400 ml-2">({acc.niche})</span>
                      </div>
                      <div className="flex items-center gap-2">
                        <span className="px-2 py-0.5 rounded bg-surfaceElevated text-gray-300">{acc.tier}</span>
                        <StatusBadge status={acc.status} />
                      </div>
                    </div>
                  ))}
                  {accounts.length === 0 && (
                    <p className="text-xs text-gray-500 py-3">No Instagram accounts connected yet.</p>
                  )}
                </div>
              </div>
            </div>
          )}

          {activeTab === "accounts" && (
            <div className="bg-surfaceCard border border-outlineSubtle rounded-2xl p-5 space-y-4">
              <div className="flex justify-between items-center">
                <h2 className="text-sm font-bold text-white">Managed Instagram Accounts ({accounts.length})</h2>
              </div>
              <div className="overflow-x-auto">
                <table className="w-full text-left text-xs">
                  <thead className="bg-surfaceElevated text-gray-400 uppercase text-[10px] tracking-wider">
                    <tr>
                      <th className="py-2.5 px-3 rounded-l-lg">Account</th>
                      <th className="py-2.5 px-3">Goal & Niche</th>
                      <th className="py-2.5 px-3">Tier</th>
                      <th className="py-2.5 px-3">Status</th>
                      <th className="py-2.5 px-3 text-right rounded-r-lg">Actions</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-outlineSubtle/50 text-gray-200">
                    {accounts.map((acc) => (
                      <tr key={acc.dsUserId} className="hover:bg-surfaceElevated/50 transition">
                        <td className="py-3 px-3">
                          <div className="font-semibold text-white">@{acc.username}</div>
                          <div className="text-[10px] text-gray-400">ID: {acc.dsUserId}</div>
                        </td>
                        <td className="py-3 px-3">
                          <div className="font-medium text-gray-200">{acc.niche}</div>
                          <div className="text-[10px] text-gray-400">{acc.goal}</div>
                        </td>
                        <td className="py-3 px-3">
                          <select
                            value={acc.tier}
                            onChange={(e) => handleAccountTier(acc.dsUserId, e.target.value)}
                            className="bg-surfaceElevated border border-outlineSubtle rounded px-2 py-1 text-xs text-white"
                          >
                            <option value="TIER_1_COLD">TIER_1_COLD</option>
                            <option value="TIER_2_WARMING">TIER_2_WARMING</option>
                            <option value="TIER_3_ESTABLISHED">TIER_3_ESTABLISHED</option>
                          </select>
                        </td>
                        <td className="py-3 px-3">
                          <StatusBadge status={acc.status} />
                          {acc.pausedUntil && (
                            <div className="text-[10px] text-roseStatus mt-0.5">Paused until: {new Date(acc.pausedUntil).toLocaleTimeString()}</div>
                          )}
                        </td>
                        <td className="py-3 px-3 text-right">
                          {acc.status.startsWith("PAUSED") ? (
                            <button
                              onClick={() => handleAccountStatus(acc.dsUserId, "ACTIVE")}
                              className="px-2.5 py-1 rounded bg-emeraldStatus/20 border border-emeraldStatus/40 text-emeraldStatus hover:bg-emeraldStatus/30 text-[11px] inline-flex items-center gap-1"
                            >
                              <Play className="w-3 h-3" /> Resume
                            </button>
                          ) : (
                            <button
                              onClick={() => handleAccountStatus(acc.dsUserId, "PAUSED_USER")}
                              className="px-2.5 py-1 rounded bg-roseStatus/20 border border-roseStatus/40 text-roseStatus hover:bg-roseStatus/30 text-[11px] inline-flex items-center gap-1"
                            >
                              <Pause className="w-3 h-3" /> Pause
                            </button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          {activeTab === "plans" && (
            <div className="space-y-4">
              <h2 className="text-sm font-bold text-white">7-Day Growth Plans ({plans.length})</h2>
              {plans.map((p) => (
                <div key={p.planId} className="bg-surfaceCard border border-outlineSubtle rounded-2xl p-5 space-y-3">
                  <div className="flex justify-between items-center text-xs">
                    <div>
                      <span className="font-semibold text-white">Plan ID: {p.planId.slice(0, 8)}...</span>
                      <span className="ml-2 text-gray-400">Account: {p.dsUserId}</span>
                    </div>
                    <span className="px-2 py-0.5 rounded bg-surfaceElevated text-magenta text-[11px] font-bold">
                      {p.status}
                    </span>
                  </div>

                  <p className="text-xs text-gray-300 bg-surfaceElevated/60 p-3 rounded-xl border border-outlineSubtle/50">
                    {p.strategySummary}
                  </p>

                  <div className="grid grid-cols-7 gap-2 text-center text-[10px]">
                    {p.dailySchedules?.map((day: any) => (
                      <div key={day.dayNumber} className="bg-surfaceElevated p-2 rounded-lg border border-outlineSubtle/40">
                        <div className="font-bold text-amberBrand">Day {day.dayNumber}</div>
                        <div className="text-gray-400 mt-1">{day.actionBudgets?.postLikes} Likes</div>
                        <div className="text-gray-400">{day.actionBudgets?.storyViews} Views</div>
                        <div className="text-gray-400">{day.actionBudgets?.comments} Cmt</div>
                      </div>
                    ))}
                  </div>

                  <div>
                    <div className="text-[11px] text-gray-400 font-semibold mb-1">Approved Comment Templates ({p.commentTemplates?.length || 0}):</div>
                    <div className="flex flex-wrap gap-1.5">
                      {p.commentTemplates?.slice(0, 6).map((c: string, idx: number) => (
                        <span key={idx} className="px-2 py-0.5 bg-surfaceElevated rounded text-[11px] text-gray-300">
                          "{c}"
                        </span>
                      ))}
                    </div>
                  </div>
                </div>
              ))}
            </div>
          )}

          {activeTab === "logs" && (
            <div className="bg-surfaceCard border border-outlineSubtle rounded-2xl p-5 space-y-4">
              <h2 className="text-sm font-bold text-white">Recent Telemetry & Execution Events</h2>
              <div className="divide-y divide-outlineSubtle/60 text-xs">
                {logs.map((log, idx) => (
                  <div key={log.id || idx} className="py-3 flex justify-between items-start gap-4">
                    <div>
                      <div className="font-semibold text-white flex items-center gap-2">
                        <span>User: {log.dsUserId}</span>
                        <span className="text-[10px] text-gray-400">{new Date(log.receivedAt).toLocaleTimeString()}</span>
                      </div>
                      <div className="mt-1 space-y-1">
                        {log.events?.map((ev: any, evIdx: number) => (
                          <div key={evIdx} className="text-gray-300 flex items-center gap-2">
                            <span className="font-mono text-amberBrand text-[11px]">{ev.actionType}</span>
                            <span className="text-gray-400">Target: {ev.targetId || "n/a"}</span>
                            <span className={`text-[10px] px-1.5 py-0.2 rounded font-semibold ${ev.outcome === "SUCCESS" ? "bg-emeraldStatus/20 text-emeraldStatus" : "bg-roseStatus/20 text-roseStatus"}`}>
                              {ev.outcome}
                            </span>
                            {ev.errorMessage && <span className="text-roseStatus text-[10px]">{ev.errorMessage}</span>}
                          </div>
                        ))}
                      </div>
                    </div>
                  </div>
                ))}
                {logs.length === 0 && <p className="text-xs text-gray-500 py-3">No execution logs received yet.</p>}
              </div>
            </div>
          )}

          {activeTab === "settings" && (
            <div className="bg-surfaceCard border border-outlineSubtle rounded-2xl p-5 space-y-4">
              <h2 className="text-sm font-bold text-white">Global Safety Ceilings (Tier 1 – 3)</h2>
              <p className="text-xs text-gray-400">
                These hard ceilings apply automatically across all accounts to prevent rate-limits and shadowbans.
              </p>
              {settings?.tierCeilings && (
                <div className="grid grid-cols-3 gap-4 pt-2">
                  {Object.entries(settings.tierCeilings).map(([tierName, limits]: any) => (
                    <div key={tierName} className="bg-surfaceElevated border border-outlineSubtle rounded-xl p-4 space-y-2 text-xs">
                      <div className="font-bold text-white text-sm">{tierName}</div>
                      <div className="flex justify-between text-gray-300">
                        <span>Max Story Views:</span> <span className="font-mono">{limits.storyViews}</span>
                      </div>
                      <div className="flex justify-between text-gray-300">
                        <span>Max Story Likes:</span> <span className="font-mono">{limits.storyLikes}</span>
                      </div>
                      <div className="flex justify-between text-gray-300">
                        <span>Max Post Likes:</span> <span className="font-mono">{limits.postLikes}</span>
                      </div>
                      <div className="flex justify-between text-gray-300">
                        <span>Max Comments:</span> <span className="font-mono">{limits.comments}</span>
                      </div>
                      <div className="flex justify-between text-gray-300">
                        <span>Max Follows:</span> <span className="font-mono">{limits.follows}</span>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </main>
      </div>
    </div>
  );
}

function NavItem({ icon, label, badge, active, onClick }: any) {
  return (
    <button
      onClick={onClick}
      className={`w-full flex items-center justify-between px-3.5 py-2.5 rounded-xl text-xs font-medium transition ${
        active
          ? "bg-gradient-to-r from-coral/15 via-magenta/15 to-amberBrand/15 text-white border border-magenta/40"
          : "text-gray-400 hover:text-white hover:bg-surfaceElevated"
      }`}
    >
      <div className="flex items-center gap-2.5">
        {icon}
        <span>{label}</span>
      </div>
      {badge && (
        <span className="px-1.5 py-0.5 rounded-md bg-surfaceElevated text-[10px] text-gray-300 font-mono">
          {badge}
        </span>
      )}
    </button>
  );
}

function KpiCard({ title, value, icon }: any) {
  return (
    <div className="bg-surfaceCard border border-outlineSubtle rounded-2xl p-4 flex items-center justify-between shadow-sm">
      <div>
        <div className="text-[11px] text-gray-400 uppercase tracking-wider font-semibold">{title}</div>
        <div className="text-xl font-bold text-white mt-1">{value}</div>
      </div>
      <div className="p-2.5 rounded-xl bg-surfaceElevated border border-outlineSubtle/60">{icon}</div>
    </div>
  );
}

function StatusBadge({ status }: { status: string }) {
  if (status === "ACTIVE") {
    return (
      <span className="px-2 py-0.5 rounded-full bg-emeraldStatus/20 text-emeraldStatus border border-emeraldStatus/40 text-[10px] font-semibold flex items-center gap-1 w-fit">
        <CheckCircle2 className="w-2.5 h-2.5" /> ACTIVE
      </span>
    );
  }
  if (status === "WARMING_UP") {
    return (
      <span className="px-2 py-0.5 rounded-full bg-warningStatus/20 text-warningStatus border border-warningStatus/40 text-[10px] font-semibold w-fit">
        WARMING UP
      </span>
    );
  }
  return (
    <span className="px-2 py-0.5 rounded-full bg-roseStatus/20 text-roseStatus border border-roseStatus/40 text-[10px] font-semibold w-fit">
      {status}
    </span>
  );
}

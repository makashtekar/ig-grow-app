import React, { useState } from "react";
import { adminFetch, setAuthToken } from "./api";
import { ShieldCheck, Sparkles } from "lucide-react";

export function LoginPage({ onLoginSuccess }: { onLoginSuccess: () => void }) {
  const [email, setEmail] = useState("admin@iggrow.studio");
  const [password, setPassword] = useState("admin123456");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      const res = await adminFetch("/admin/auth/login", {
        method: "POST",
        body: JSON.stringify({ email, password }),
      });
      const data = await res.json();
      if (!res.ok) {
        throw new Error(data.error || "Login failed");
      }
      setAuthToken(data.token);
      onLoginSuccess();
    } catch (err: any) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center p-4 bg-obsidian">
      <div className="max-w-md w-full bg-surfaceCard border border-outlineSubtle rounded-2xl p-8 shadow-2xl">
        <div className="flex items-center gap-3 mb-6">
          <div className="w-10 h-10 rounded-xl bg-gradient-to-tr from-coral via-magenta to-amberBrand flex items-center justify-center shadow-lg">
            <Sparkles className="w-5 h-5 text-white" />
          </div>
          <div>
            <h1 className="text-xl font-bold text-white tracking-wide">IG Grow Studio</h1>
            <p className="text-xs text-gray-400">Admin Control Center</p>
          </div>
        </div>

        {error && (
          <div className="mb-4 p-3 rounded-lg bg-roseStatus/10 border border-roseStatus/40 text-roseStatus text-sm">
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="block text-xs font-semibold text-gray-300 uppercase tracking-wider mb-1">
              Admin Email
            </label>
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="w-full bg-surfaceElevated border border-outlineSubtle rounded-xl px-4 py-2.5 text-white focus:outline-none focus:border-magenta transition"
              required
            />
          </div>

          <div>
            <label className="block text-xs font-semibold text-gray-300 uppercase tracking-wider mb-1">
              Password
            </label>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="w-full bg-surfaceElevated border border-outlineSubtle rounded-xl px-4 py-2.5 text-white focus:outline-none focus:border-magenta transition"
              required
            />
          </div>

          <button
            type="submit"
            disabled={loading}
            className="w-full py-3 px-4 rounded-xl bg-gradient-to-r from-coral via-magenta to-amberBrand text-white font-medium shadow-md hover:opacity-95 transition disabled:opacity-50 flex items-center justify-center gap-2"
          >
            <ShieldCheck className="w-4 h-4" />
            {loading ? "Authenticating..." : "Sign In to Admin Panel"}
          </button>
        </form>
      </div>
    </div>
  );
}

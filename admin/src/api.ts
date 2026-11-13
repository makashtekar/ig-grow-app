const API_BASE = import.meta.env.VITE_API_URL || "http://localhost:8080";

export function getAuthToken(): string | null {
  return localStorage.getItem("ig_grow_admin_token");
}

export function setAuthToken(token: string) {
  localStorage.setItem("ig_grow_admin_token", token);
}

export function clearAuthToken() {
  localStorage.removeItem("ig_grow_admin_token");
}

export async function adminFetch(endpoint: string, options: RequestInit = {}) {
  const token = getAuthToken();
  const headers = new Headers(options.headers || {});
  headers.set("Content-Type", "application/json");
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  const res = await fetch(`${API_BASE}${endpoint}`, {
    ...options,
    headers,
  });

  if (res.status === 401 && !endpoint.includes("/auth/login")) {
    clearAuthToken();
    window.location.reload();
  }

  return res;
}

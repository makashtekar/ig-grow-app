import { useEffect, useState } from "react";
import { getAuthToken } from "./api";
import { Dashboard } from "./Dashboard";
import { LoginPage } from "./LoginPage";

export function App() {
  const [isAuthenticated, setIsAuthenticated] = useState<boolean>(false);

  useEffect(() => {
    const token = getAuthToken();
    setIsAuthenticated(Boolean(token));
  }, []);

  if (!isAuthenticated) {
    return <LoginPage onLoginSuccess={() => setIsAuthenticated(true)} />;
  }

  return <Dashboard />;
}

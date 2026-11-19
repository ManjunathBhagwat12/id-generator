import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import * as api from './api.js';

const AuthContext = createContext(null);
export const useAuth = () => useContext(AuthContext);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);      // username or null
  const [ready, setReady] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const r = await api.me();
      setUser(r.authenticated ? r.username : null);
    } catch {
      setUser(null);
    } finally {
      setReady(true);
    }
  }, []);

  useEffect(() => {
    api.setUnauthorizedHandler(() => setUser(null)); // session expired mid-use
    refresh();
  }, [refresh]);

  const value = useMemo(() => ({
    user,
    ready,
    async login(username, password) {
      await api.login(username, password);
      await refresh();
    },
    async logout() {
      try { await api.logout(); } finally { setUser(null); await api.me().catch(() => {}); }
    },
  }), [user, ready, refresh]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

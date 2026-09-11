import { createContext, useContext, useState, useCallback } from 'react';
import { api } from './api.js';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(() => {
    const raw = localStorage.getItem('st_user');
    return raw ? JSON.parse(raw) : null;
  });
  const [token] = useState(() => localStorage.getItem('st_token'));

  const login = useCallback(async (email, password) => {
    const res = await api.post('/auth/login', { email, password });
    localStorage.setItem('st_token', res.token);
    localStorage.setItem('st_user', JSON.stringify(res.user));
    setUser(res.user);
    return res.user;
  }, []);

  const logout = useCallback(() => {
    localStorage.removeItem('st_token');
    localStorage.removeItem('st_user');
    setUser(null);
  }, []);

  const refreshMe = useCallback(async () => {
    try {
      const res = await api.get('/auth/me');
      setUser(res.user);
      localStorage.setItem('st_user', JSON.stringify(res.user));
    } catch {
      logout();
    }
  }, [logout]);

  return (
    <AuthContext.Provider value={{ user, token, login, logout, refreshMe }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  return useContext(AuthContext);
}
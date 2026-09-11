import { useState, useEffect } from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../lib/AuthContext.jsx';
import { api } from '../lib/api.js';

const ICONS = {
  dashboard: '📊',
  leads: '🧲',
  tasks: '✅',
  packages: '🎒',
  payments: '💳',
  operations: '🚌',
  customers: '👥',
  reports: '📈',
};

const SIDEBAR = [
  { to: '/', label: 'Dashboard', icon: ICONS.dashboard },
  { to: '/leads', label: 'Leads', icon: ICONS.leads },
  { to: '/tasks', label: 'Follow-ups', icon: ICONS.tasks },
  { to: '/targets', label: 'Targets', icon: ICONS.dashboard },
  { to: '/packages', label: 'Packages', icon: ICONS.packages },
  { to: '/payments', label: 'Payments', icon: ICONS.payments },
  { to: '/operations', label: 'Operations', icon: ICONS.operations },
  { to: '/customers', label: 'Customers', icon: ICONS.customers },
  { to: '/reports', label: 'Marketing', icon: ICONS.reports },
];

export default function Layout() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const [unread, setUnread] = useState(0);
  const [showNotif, setShowNotif] = useState(false);
  const [notifs, setNotifs] = useState([]);
  const [menuOpen, setMenuOpen] = useState(false);

  async function loadNotifs() {
    try {
      const res = await api.get('/notifications');
      setNotifs(res.notifications);
      setUnread(res.notifications.filter((n) => !n.read_at && n.channel === 'in_app').length);
    } catch {}
  }

  useEffect(() => {
    loadNotifs();
    const t = setInterval(loadNotifs, 60000);
    return () => clearInterval(t);
  }, []);

  async function markReadAll() {
    await api.post('/notifications/read-all', {});
    loadNotifs();
  }

  function go(to) {
    setMenuOpen(false);
    setShowNotif(false);
    navigate(to);
  }

  return (
    <div className="min-h-screen bg-slate-100">
      {/* Desktop sidebar */}
      <aside className="hidden md:flex fixed inset-y-0 left-0 w-60 bg-slate-900 flex-col">
        <div className="px-4 py-5 border-b border-slate-800">
          <div className="text-white font-bold text-lg">SecureTravels</div>
          <div className="text-xs text-slate-400">CRM · Phase 1</div>
        </div>
        <nav className="flex-1 p-3 space-y-1 overflow-y-auto">
          {SIDEBAR.map((item) => (
            <NavLink key={item.to} to={item.to} className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
              <span>{item.icon}</span>
              {item.label}
              {item.to === '/tasks' && unread > 0 && (
                <span className="ml-auto bg-rose-500 text-white text-[10px] rounded-full px-1.5 py-0.5">{unread}</span>
              )}
            </NavLink>
          ))}
        </nav>
        <div className="p-3 border-t border-slate-800">
          <div className="text-sm text-white font-medium truncate">{user?.full_name}</div>
          <div className="flex items-center justify-between mt-1">
            <span className="text-xs text-slate-400 capitalize">{user?.role}</span>
            <button onClick={logout} className="text-xs text-slate-400 hover:text-white">
              Logout
            </button>
          </div>
        </div>
      </aside>

      {/* Mobile top bar */}
      <header className="md:hidden fixed top-0 inset-x-0 z-30 bg-slate-900 text-white flex items-center justify-between px-4 py-3">
        <button onClick={() => setMenuOpen(!menuOpen)} className="text-xl">☰</button>
        <div className="font-bold">SecureTravels CRM</div>
        <div className="relative">
          <button onClick={() => { setShowNotif(!showNotif); setMenuOpen(false); }} className="text-lg relative">
            🔔
            {unread > 0 && (
              <span className="absolute -top-1 -right-1 bg-rose-500 text-white text-[9px] rounded-full w-3.5 h-3.5 flex items-center justify-center">{unread}</span>
            )}
          </button>
          {showNotif && (
            <div className="absolute right-0 top-9 w-72 bg-white rounded-xl shadow-lg border border-slate-200 text-slate-800 max-h-80 overflow-y-auto z-40">
              <div className="flex items-center justify-between px-3 py-2 border-b">
                <span className="text-xs font-semibold">Notifications</span>
                <button onClick={markReadAll} className="text-[11px] text-indigo-600">Mark all read</button>
              </div>
              {notifs.length === 0 && <div className="p-3 text-xs text-slate-500">No notifications</div>}
              {notifs.map((n) => (
                <div key={n.id} className={`px-3 py-2 border-b text-xs ${!n.read_at ? 'bg-indigo-50' : ''}`}>
                  <div className="font-medium">{n.subject}</div>
                  <div className="text-slate-600 line-clamp-2">{n.message}</div>
                </div>
              ))}
            </div>
          )}
        </div>
      </header>

      {/* Mobile drawer */}
      {menuOpen && (
        <div className="md:hidden fixed inset-0 z-40 bg-black/50" onClick={() => setMenuOpen(false)}>
          <div className="absolute inset-y-0 left-0 w-64 bg-slate-900 p-4" onClick={(e) => e.stopPropagation()}>
            <div className="text-white font-bold mb-4">SecureTravels</div>
            <nav className="space-y-1">
              {SIDEBAR.map((item) => (
                <button
                  key={item.to}
                  onClick={() => go(item.to)}
                  className="w-full text-left flex items-center gap-3 px-3 py-2 rounded-lg text-sm text-slate-300 hover:bg-white/10 hover:text-white"
                >
                  <span>{item.icon}</span>{item.label}
                </button>
              ))}
              <button onClick={logout} className="w-full text-left px-3 py-2 rounded-lg text-sm text-rose-400 hover:bg-white/10 mt-4">
                Logout ({user?.full_name})
              </button>
            </nav>
          </div>
        </div>
      )}

      {/* Main */}
      <main className="md:pl-60 pt-14 md:pt-0 pb-20 md:pb-6 px-3 md:px-6">
        <div className="max-w-7xl mx-auto">
          <Outlet />
        </div>
      </main>

      {/* Mobile bottom nav */}
      <nav className="md:hidden fixed bottom-0 inset-x-0 z-30 bg-white border-t border-slate-200 grid grid-cols-5">
        {[
          { to: '/', label: 'Home', icon: '📊' },
          { to: '/leads', label: 'Leads', icon: '🧲' },
          { to: '/tasks', label: 'Tasks', icon: '✅' },
          { to: '/operations', label: 'Ops', icon: '🚌' },
          { to: '/customers', label: 'Cust', icon: '👥' },
        ].map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            className={({ isActive }) => `flex flex-col items-center py-2 text-[10px] text-slate-500 ${isActive ? 'text-indigo-600 font-semibold' : ''}`}
          >
            <span className="text-base leading-none">{item.icon}</span>
            {item.label}
          </NavLink>
        ))}
      </nav>
    </div>
  );
}
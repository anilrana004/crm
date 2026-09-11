import { useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '../lib/AuthContext.jsx';

export default function Login() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState('admin@securetravels.in');
  const [password, setPassword] = useState('admin123');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      const user = await login(email, password);
      const dest = user.role === 'ops' ? '/operations' : '/';
      navigate(dest, { replace: true });
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-900 via-slate-800 to-indigo-900 flex items-center justify-center p-4">
      <div className="w-full max-w-md">
        <div className="text-center mb-6">
          <div className="text-3xl font-bold text-white">🏔️ SecureTravels</div>
          <div className="text-slate-300 text-sm mt-1">Sales &amp; Operations CRM</div>
        </div>
        <form onSubmit={submit} className="bg-white rounded-2xl shadow-xl p-6 space-y-4">
          <div>
            <label className="label">Email</label>
            <input className="input" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
          </div>
          <div>
            <label className="label">Password</label>
            <input className="input" type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
          </div>
          {error && <div className="text-sm text-rose-600 bg-rose-50 rounded-lg px-3 py-2">{error}</div>}
          <button className="btn-primary w-full py-2.5" disabled={busy}>
            {busy ? 'Signing in…' : 'Sign in'}
          </button>
          <div className="text-[11px] text-slate-400 leading-relaxed">
            Demo access —<br />
            admin@securetravels.in / admin123<br />
            sales.ravi@securetravels.in / sales123 · ops.suresh@securetravels.in / ops123
          </div>
        </form>
      </div>
    </div>
  );
}
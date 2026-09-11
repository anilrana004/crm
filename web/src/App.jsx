import { useEffect } from 'react';
import { Routes, Route, Navigate, useLocation } from 'react-router-dom';
import { useAuth } from './lib/AuthContext.jsx';
import Layout from './components/Layout.jsx';
import Login from './pages/Login.jsx';
import Dashboard from './pages/Dashboard.jsx';
import Leads from './pages/Leads.jsx';
import LeadDetail from './pages/LeadDetail.jsx';
import Tasks from './pages/Tasks.jsx';
import Packages from './pages/Packages.jsx';
import Payments from './pages/Payments.jsx';
import Operations from './pages/Operations.jsx';
import Customers from './pages/Customers.jsx';
import Reports from './pages/Reports.jsx';
import Targets from './pages/Targets.jsx';

function Protected({ children }) {
  const { user } = useAuth();
  const location = useLocation();
  if (!user) return <Navigate to="/login" state={{ from: location }} replace />;
  return children;
}

export default function App() {
  const { user, refreshMe } = useAuth();
  useEffect(() => {
    if (user) refreshMe();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route
        path="/"
        element={
          <Protected>
            <Layout />
          </Protected>
        }
      >
        <Route index element={<Dashboard />} />
        <Route path="leads" element={<Leads />} />
        <Route path="leads/:id" element={<LeadDetail />} />
        <Route path="tasks" element={<Tasks />} />
        <Route path="packages" element={<Packages />} />
        <Route path="payments" element={<Payments />} />
        <Route path="operations" element={<Operations />} />
        <Route path="customers" element={<Customers />} />
        <Route path="targets" element={<Targets />} />
        <Route path="reports" element={<Reports />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
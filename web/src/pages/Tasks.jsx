import { useEffect, useState, useCallback } from 'react';
import { Link } from 'react-router-dom';
import { api, TASK_STATUS, fmtDateTime } from '../lib/api.js';
import { Badge, Spinner, Empty } from '../components/ui.jsx';

export default function Tasks() {
  const [tasks, setTasks] = useState([]);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState('pending');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get(`/tasks?status=${tab}`);
      setTasks(res.tasks);
    } finally {
      setLoading(false);
    }
  }, [tab]);

  useEffect(() => {
    load();
  }, [load]);

  async function complete(taskId) {
    await api.patch(`/tasks/${taskId}`, { status: 'completed' });
    load();
  }

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-bold text-slate-800">Follow-ups &amp; Tasks</h1>

      <div className="flex gap-2">
        {[['pending', 'Pending'], ['overdue', 'Overdue'], ['completed', 'Completed']].map(([k, v]) => (
          <button
            key={k}
            onClick={() => setTab(k)}
            className={`btn ${tab === k ? 'btn-primary' : 'btn-outline'}`}
          >
            {v}
          </button>
        ))}
      </div>

      {loading ? <Spinner /> : tasks.length === 0 ? <Empty message="No tasks in this view" /> : (
        <div className="space-y-2">
          {tasks.map((t) => (
            <div key={t.id} className="card flex flex-wrap items-center justify-between gap-2">
              <div className="min-w-0">
                <Link to={`/leads/${t.lead_id}`} className="text-sm font-semibold text-slate-800 hover:text-indigo-600">
                  {t.title}
                </Link>
                <div className="text-xs text-slate-500 mt-0.5">
                  Customer: {t.customer_name} ({t.mobile_number}) · {t.assignee_name} · due {fmtDateTime(t.due_at)}
                </div>
              </div>
              <div className="flex items-center gap-2">
                <Badge cls={TASK_STATUS[t.status]}>{t.status}</Badge>
                {t.status === 'pending' && (
                  <button className="btn-success btn-sm" onClick={() => complete(t.id)}>✓ Done</button>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
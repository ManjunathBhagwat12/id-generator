import { useCallback, useEffect, useState } from 'react';
import { getAnalytics, listTemplates, tenantSummary } from '../api.js';
import { Alert, Button, Card, EmptyState, LoadingRow, PageHeader, Spinner } from '../components/ui.jsx';
import { DataTable, TemplateBarChart, TrendChart, fmtDay, fmtNum } from '../components/charts.jsx';

function Stat({ label, value, note, tone = '' }) {
  return (
    <div className={`stat ${tone}`}>
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value == null ? '–' : fmtNum(value)}</div>
      {note && <div className="stat-note">{note}</div>}
    </div>
  );
}

const PERIODS = [{ value: '7d', label: 'Last 7 days' }, { value: '30d', label: 'Last 30 days' }];

export default function Dashboard() {
  const [summary, setSummary] = useState(null);
  const [templates, setTemplates] = useState(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  const [period, setPeriod] = useState('7d');
  const [analytics, setAnalytics] = useState(null);
  const [aLoading, setALoading] = useState(true);
  const [aError, setAError] = useState('');

  const loadSummary = useCallback(async () => {
    setLoading(true);
    try {
      const [s, t] = await Promise.all([tenantSummary(), listTemplates()]);
      setSummary(s);
      setTemplates(t);
      setError('');
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, []);

  const loadAnalytics = useCallback(async (p) => {
    setALoading(true);
    try {
      setAnalytics(await getAnalytics(p));
      setAError('');
    } catch (e) {
      setAError(e.message);
    } finally {
      setALoading(false);
    }
  }, []);

  useEffect(() => { loadSummary(); }, [loadSummary]);
  useEffect(() => { loadAnalytics(period); }, [loadAnalytics, period]);

  const refresh = () => { loadSummary(); loadAnalytics(period); };
  const issuable = templates?.filter((t) => t.issuable).length;
  const periodLabel = PERIODS.find((p) => p.value === period).label.toLowerCase();
  // Only show data that matches the selected period (never a previous period's numbers under a new label).
  const current = analytics?.period === period ? analytics : null;
  const empty = current && current.issuedInPeriod === 0;
  const busiest = current && current.daily.reduce((a, d) => (d.count > a.count ? d : a), current.daily[0]);

  return (
    <>
      <PageHeader
        title="Admin dashboard"
        description="Overview of tenants, templates and ID issuance."
        actions={<Button variant="secondary" onClick={refresh} busy={loading || aLoading} busyText="Refreshing…">Refresh</Button>}
      />
      {error && <Alert kind="error" title="Could not load the summary">{error}</Alert>}

      <div className="stats" aria-busy={loading || aLoading}>
        <Stat label="Total IDs issued" value={analytics?.totalIssued}
              note={current ? `${fmtNum(current.issuedInPeriod)} in the ${periodLabel}` : undefined} tone="info" />
        <Stat label="Total tenants" value={summary?.total} note="All registered cities" />
        <Stat label="Active tenants" value={summary?.active} note="Can issue IDs" tone="success" />
        <Stat label="Inactive tenants" value={summary?.inactive} note="Cannot issue IDs" tone="warning" />
        <Stat label="Templates" value={templates?.length} note={templates ? `${issuable} ready to issue` : undefined} />
      </div>

      <div className="section-head">
        <h2>ID issuance</h2>
        <div className="section-meta">
          {aLoading && analytics && <span className="loading-inline"><Spinner /> Updating…</span>}
          {current && <span>{fmtDay(current.from)} – {fmtDay(current.to)} · days in {current.timezone}</span>}
          <div className="segmented" role="radiogroup" aria-label="Period">
            {PERIODS.map((p) => (
              <label key={p.value}>
                <input type="radio" name="period" value={p.value} checked={period === p.value} onChange={() => setPeriod(p.value)} />
                <span>{p.label}</span>
              </label>
            ))}
          </div>
        </div>
      </div>

      {aError && (
        <Alert kind="error" title="Could not load issuance analytics">
          {aError} <Button variant="ghost" size="sm" onClick={() => loadAnalytics(period)}>Try again</Button>
        </Alert>
      )}

      {!current && aLoading ? (
        <Card><LoadingRow label="Loading issuance analytics…" /></Card>
      ) : current && (
        <div className="charts">
          <Card title="IDs issued per day">
            <p className="chart-sub">
              {empty ? `No IDs were issued in the ${periodLabel}.`
                : `${fmtNum(current.issuedInPeriod)} IDs in the ${periodLabel}; busiest day ${fmtDay(busiest.date)} with ${fmtNum(busiest.count)}.`}
            </p>
            {empty ? (
              <EmptyState title="No issuance in this period">IDs generated from any template will appear here.</EmptyState>
            ) : (
              <>
                <div className="chart-box">
                  <TrendChart daily={current.daily}
                              label={`Line chart of IDs issued per day, ${periodLabel}: ${fmtNum(current.issuedInPeriod)} in total, busiest day ${fmtDay(busiest.date)} with ${fmtNum(busiest.count)}. A data table follows.`} />
                </div>
                <DataTable caption={`IDs issued per day, ${periodLabel}`} columns={['Date', 'IDs issued']}
                           rows={[...current.daily].reverse().map((d) => [fmtDay(d.date, true), d.count])} />
              </>
            )}
          </Card>

          <Card title="IDs issued by template">
            <p className="chart-sub">{`All ${current.byTemplate.length} templates, ${periodLabel}, highest first.`}</p>
            {empty ? (
              <EmptyState title="No issuance in this period">Counts per template will appear once IDs are issued.</EmptyState>
            ) : (
              <>
                <TemplateBarChart rows={current.byTemplate}
                                  label={`Horizontal bar chart of IDs issued per template, ${periodLabel}. Highest: ${current.byTemplate[0].templateId} with ${fmtNum(current.byTemplate[0].count)}. A data table follows.`} />
                <DataTable caption={`IDs issued per template, ${periodLabel}`} columns={['Template', 'Name', 'IDs issued']}
                           rows={current.byTemplate.map((t) => [t.templateId, t.name, t.count])} />
              </>
            )}
          </Card>
        </div>
      )}

      <div className="nav-cards" style={{ marginTop: 28 }}>
        <a className="nav-card" href="#/admin/tenants">
          <h2>Tenant management</h2>
          <p>Search tenants, add new cities, edit names and codes, activate or deactivate.</p>
        </a>
        <a className="nav-card" href="#/admin/templates">
          <h2>Template management</h2>
          <p>View template formats and create new templates. Templates are immutable.</p>
        </a>
        <a className="nav-card" href="#/admin/credentials">
          <h2>API credentials</h2>
          <p>Create, rotate and revoke API keys for external applications.</p>
        </a>
        <a className="nav-card" href="#/">
          <h2>ID Generator</h2>
          <p>Issue single or bulk IDs and copy the results.</p>
        </a>
      </div>
    </>
  );
}

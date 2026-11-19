import { useCallback, useEffect, useMemo, useState } from 'react';
import { generate, getCities, getRecent, getTemplates } from '../api.js';
import {
  Alert, Badge, Button, Card, CopyButton, EmptyState, Field, FormatExpression, LoadingRow, PageHeader, formatDate,
} from '../components/ui.jsx';

const MAX_COUNT = 1000;

function countError(raw) {
  if (raw === '') return 'Enter how many IDs to generate.';
  const n = Number(raw);
  if (!Number.isInteger(n)) return 'Enter a whole number.';
  if (n < 1 || n > MAX_COUNT) return `Enter a number between 1 and ${MAX_COUNT}.`;
  return null;
}

export default function Generator() {
  const [templates, setTemplates] = useState([]);
  const [cities, setCities] = useState([]);
  const [templateId, setTemplateId] = useState('');
  const [city, setCity] = useState('');
  const [mode, setMode] = useState('single'); // single | bulk
  const [count, setCount] = useState('10');
  const [countTouched, setCountTouched] = useState(false);

  const [configLoading, setConfigLoading] = useState(true);
  const [initError, setInitError] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [result, setResult] = useState(null); // {ids, template, city, at}

  const [recent, setRecent] = useState([]);
  const [recentLoading, setRecentLoading] = useState(true);
  const [recentError, setRecentError] = useState('');

  const loadRecent = useCallback(async () => {
    setRecentLoading(true);
    try {
      setRecent(await getRecent());
      setRecentError('');
    } catch (e) {
      setRecentError(e.message);
    } finally {
      setRecentLoading(false);
    }
  }, []);

  useEffect(() => {
    (async () => {
      try {
        const [t, c] = await Promise.all([getTemplates(), getCities()]); // c = active tenants only
        setTemplates(t);
        const sorted = [...c].sort((a, b) => a.cityName.localeCompare(b.cityName));
        setCities(sorted);
        setTemplateId(t.find((x) => x.enabled)?.id ?? '');
        setCity(sorted[0]?.cityCode ?? '');
      } catch (e) {
        setInitError(`Could not load templates and cities: ${e.message}`);
      } finally {
        setConfigLoading(false);
      }
    })();
    loadRecent();
  }, [loadRecent]);

  const selected = templates.find((t) => t.id === templateId);
  const selectedCity = cities.find((c) => c.cityCode === city);
  const cError = mode === 'bulk' ? countError(count) : null;
  const canSubmit = !busy && !configLoading && selected?.enabled && city && !cError;
  const enabledCount = useMemo(() => templates.filter((t) => t.enabled).length, [templates]);

  async function onSubmit(e) {
    e.preventDefault();
    setCountTouched(true);
    if (!canSubmit) return;
    const n = mode === 'bulk' ? Number(count) : 1;
    setError('');
    setBusy(true);
    try {
      const ids = await generate(templateId, city, n);
      setResult({ ids, template: selected, city: selectedCity, at: new Date() });
      loadRecent();
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <PageHeader
        title="ID Generator"
        description="Issue unique, formatted identifiers for a city from any enabled template. Every issued ID is permanent and never reused."
      />

      {initError && <Alert kind="error" title="Configuration unavailable">{initError}</Alert>}

      <div className="gen-layout">
        <Card title="Generate">
          {configLoading ? <LoadingRow label="Loading templates and cities…" /> : (
            <form className="form" onSubmit={onSubmit} noValidate>
              <Field label="Template" required hint={`${enabledCount} of ${templates.length} templates can issue IDs.`}>
                <select value={templateId} onChange={(e) => setTemplateId(e.target.value)}>
                  {templates.map((t) => (
                    <option key={t.id} value={t.id} disabled={!t.enabled}>
                      {t.name} ({t.id}){t.enabled ? '' : ' – unavailable'}
                    </option>
                  ))}
                </select>
              </Field>

              {selected && (
                <div className="template-info" aria-label="Selected template">
                  <div className="row"><span className="k">Format</span><FormatExpression format={selected.format} /></div>
                  <div className="row"><span className="k">Counter</span><span className="small">{selected.counterScope}</span></div>
                </div>
              )}

              <Field label="City / tenant" required hint="Only active tenants are listed.">
                <select value={city} onChange={(e) => setCity(e.target.value)}>
                  {cities.length === 0 && <option value="">No active tenants</option>}
                  {cities.map((c) => (
                    <option key={c.tenantId} value={c.cityCode}>{c.cityName} ({c.cityCode}) · {c.tenantId}</option>
                  ))}
                </select>
              </Field>

              <fieldset className="field" style={{ border: 0, padding: 0, margin: 0 }}>
                <legend className="field-label">Quantity</legend>
                <div className="segmented" role="radiogroup" aria-label="Quantity">
                  <label>
                    <input type="radio" name="mode" value="single" checked={mode === 'single'} onChange={() => setMode('single')} />
                    <span>Single ID</span>
                  </label>
                  <label>
                    <input type="radio" name="mode" value="bulk" checked={mode === 'bulk'} onChange={() => setMode('bulk')} />
                    <span>Bulk</span>
                  </label>
                </div>
              </fieldset>

              {mode === 'bulk' && (
                <Field label="Number of IDs" required hint={`Between 1 and ${MAX_COUNT}. Issued as one contiguous, ordered range.`}
                       error={countTouched ? cError : null}>
                  <input type="number" inputMode="numeric" min="1" max={MAX_COUNT} value={count}
                         onChange={(e) => setCount(e.target.value)} onBlur={() => setCountTouched(true)} />
                </Field>
              )}

              {error && <Alert kind="error" title="IDs were not generated" onClose={() => setError('')}>{error}</Alert>}

              <Button type="submit" size="lg" className="btn-block" busy={busy} busyText="Generating…" disabled={!canSubmit}>
                {mode === 'bulk' && !cError ? `Generate ${Number(count).toLocaleString()} IDs` : 'Generate ID'}
              </Button>
            </form>
          )}
        </Card>

        <Card
          title="Generated IDs"
          aria-live="polite"
          actions={result && result.ids.length > 1 && (
            <CopyButton text={result.ids.join('\n')} label="Copy all" describe={`${result.ids.length} IDs`} variant="secondary" />
          )}
        >
          {!result ? (
            <EmptyState title="No IDs generated yet">Choose a template and city, then select Generate.</EmptyState>
          ) : (
            <>
              <div className="result-meta">
                <span><b>{result.ids.length.toLocaleString()}</b> {result.ids.length === 1 ? 'ID' : 'IDs'} issued</span>
                <span>Template <b>{result.template.id}</b></span>
                <span>City <b>{result.city?.cityName ?? ''} ({result.city?.cityCode})</b></span>
                <span>{formatDate(result.at, true)}</span>
              </div>
              {result.ids.length === 1 ? (
                <div className="id-single">
                  <code>{result.ids[0]}</code>
                  <CopyButton text={result.ids[0]} describe={result.ids[0]} variant="primary" size="md" />
                </div>
              ) : (
                <ol className="id-list" aria-label="Generated IDs">
                  {result.ids.map((id, i) => (
                    <li key={id}>
                      <span className="n" aria-hidden="true">{i + 1}</span>
                      <code>{id}</code>
                      <CopyButton text={id} describe={id} />
                    </li>
                  ))}
                </ol>
              )}
            </>
          )}
        </Card>
      </div>

      <Card
        className="flush"
        title="Recently issued IDs"
        actions={<Button variant="secondary" size="sm" onClick={loadRecent} busy={recentLoading} busyText="Refreshing…">Refresh</Button>}
      >
        {recentError && <div style={{ padding: '0 20px' }}><Alert kind="error">{recentError}</Alert></div>}
        {recentLoading && recent.length === 0 ? <LoadingRow /> : recent.length === 0 ? (
          <EmptyState title="Nothing issued yet">The 20 most recent IDs will appear here.</EmptyState>
        ) : (
          <div className="table-wrap" style={{ marginTop: 12 }}>
            <table>
              <caption className="sr-only">The 20 most recently issued IDs, newest first</caption>
              <thead>
                <tr><th scope="col">ID</th><th scope="col">Template</th><th scope="col">Tenant</th><th scope="col">Issued</th><th scope="col" className="actions"><span className="sr-only">Actions</span></th></tr>
              </thead>
              <tbody>
                {recent.map((r) => (
                  <tr key={r.id}>
                    <td><code className="cell-strong">{r.id}</code></td>
                    <td><Badge tone="info">{r.templateId}</Badge></td>
                    <td>{r.tenantId}</td>
                    <td className="cell-sub">{formatDate(r.createdAt, true)}</td>
                    <td className="actions"><CopyButton text={r.id} describe={r.id} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  );
}

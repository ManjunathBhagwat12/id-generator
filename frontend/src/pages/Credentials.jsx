import { useCallback, useEffect, useRef, useState } from 'react';
import {
  createCredential, getCredential, listCredentials, listTemplates, listTenants, revokeCredential, rotateCredential,
} from '../api.js';
import {
  Alert, Badge, Button, Card, ConfirmDialog, CopyButton, EmptyState, Field, LoadingRow, Modal, PageHeader, formatDate,
} from '../components/ui.jsx';

const NAME_RULE = /^[\p{L}\p{M}0-9][\p{L}\p{M}0-9 .,'()&/_-]*$/u;

function Templates({ list, max = 3 }) {
  const shown = list.slice(0, max);
  return (
    <span className="tag-list">
      {shown.map((t) => <span key={t} className="tag">{t}</span>)}
      {list.length > max && <span className="tag" title={list.slice(max).join(', ')}>+{list.length - max}</span>}
    </span>
  );
}

const StatusBadge = ({ status }) => <Badge tone={status === 'ACTIVE' ? 'success' : 'neutral'}>{status === 'ACTIVE' ? 'Active' : 'Revoked'}</Badge>;

export default function Credentials() {
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [opening, setOpening] = useState('');
  const [dialog, setDialog] = useState(null); // create | view | rotate | revoke | key
  const noticeTimer = useRef();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setItems(await listCredentials());
      setError('');
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => { load(); }, [load]);
  useEffect(() => () => clearTimeout(noticeTimer.current), []);

  const flash = (m) => {
    setNotice(m);
    clearTimeout(noticeTimer.current);
    noticeTimer.current = setTimeout(() => setNotice(''), 6000);
  };

  async function view(c) {
    setOpening(c.id);
    try {
      setDialog({ type: 'view', detail: await getCredential(c.id) });
    } catch (e) {
      setError(e.message);
    } finally {
      setOpening('');
    }
  }

  return (
    <>
      <PageHeader
        title="API credentials"
        description="Keys for external applications. Each key can issue IDs only for its tenant and permitted templates, and can never access admin functions."
        actions={<Button onClick={() => setDialog({ type: 'create' })}>Create credential</Button>}
      />
      {error && <Alert kind="error" onClose={() => setError('')}>{error}</Alert>}
      {notice && <Alert kind="success" onClose={() => setNotice('')}>{notice}</Alert>}

      <Card className="flush">
        {loading && items.length === 0 ? <LoadingRow label="Loading credentials…" /> : items.length === 0 ? (
          <EmptyState title="No API credentials yet">Create a credential to let an external application request IDs.</EmptyState>
        ) : (
          <>
            <div className="table-wrap">
              <table aria-busy={loading}>
                <caption className="sr-only">API credentials</caption>
                <thead>
                  <tr>
                    <th scope="col">Application</th>
                    <th scope="col">Tenant</th>
                    <th scope="col">Templates</th>
                    <th scope="col">Key</th>
                    <th scope="col">Status</th>
                    <th scope="col" className="hide-sm">Created</th>
                    <th scope="col" className="hide-sm">Last used</th>
                    <th scope="col" className="actions"><span className="sr-only">Actions</span></th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((c) => (
                    <tr key={c.id} className={c.status === 'ACTIVE' ? '' : 'is-inactive'}>
                      <td>
                        <div className="cell-strong">{c.appName}</div>
                        {c.description && <div className="cell-sub">{c.description.length > 60 ? `${c.description.slice(0, 60)}…` : c.description}</div>}
                      </td>
                      <td>{c.cityName}<div className="cell-sub"><code>{c.tenantId}</code></div></td>
                      <td><Templates list={c.templates} /></td>
                      <td><code>{c.keyPrefix}…</code></td>
                      <td><StatusBadge status={c.status} /></td>
                      <td className="cell-sub hide-sm">{formatDate(c.createdAt)}</td>
                      <td className="cell-sub hide-sm">{c.lastUsedAt ? formatDate(c.lastUsedAt, true) : 'Never'}</td>
                      <td className="actions">
                        <Button variant="ghost" size="sm" onClick={() => view(c)} busy={opening === c.id} aria-label={`View ${c.appName}`}>View</Button>
                        {c.status === 'ACTIVE' && (
                          <>
                            <Button variant="ghost" size="sm" onClick={() => setDialog({ type: 'rotate', c })} aria-label={`Rotate key of ${c.appName}`}>Rotate</Button>
                            <Button variant="ghost" size="sm" onClick={() => setDialog({ type: 'revoke', c })} aria-label={`Revoke ${c.appName}`}>Revoke</Button>
                          </>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="table-foot">
              {items.filter((c) => c.status === 'ACTIVE').length} active · {items.filter((c) => c.status !== 'ACTIVE').length} revoked
            </div>
          </>
        )}
      </Card>

      {dialog?.type === 'create' && (
        <CreateCredential
          onCancel={() => setDialog(null)}
          onCreated={(r) => { setDialog({ type: 'key', issued: r, title: 'API key created' }); load(); }}
        />
      )}
      {dialog?.type === 'key' && (
        <KeyReveal issued={dialog.issued} title={dialog.title}
                   onDone={() => { flash(`Credential "${dialog.issued.credential.appName}" is ready. The full key is no longer shown.`); setDialog(null); }} />
      )}
      {dialog?.type === 'rotate' && (
        <ConfirmDialog
          title={`Rotate the key for ${dialog.c.appName}?`}
          confirmLabel="Rotate key" busyText="Rotating…"
          onCancel={() => setDialog(null)}
          onConfirm={async () => {
            const r = await rotateCredential(dialog.c.id);
            setDialog({ type: 'key', issued: r, title: 'API key rotated' });
            load();
          }}
        >
          <p>A new key is generated. The current key <code>{dialog.c.keyPrefix}…</code> <b>stops working immediately</b>, so update the application right after copying the new key.</p>
        </ConfirmDialog>
      )}
      {dialog?.type === 'revoke' && (
        <ConfirmDialog
          title={`Revoke ${dialog.c.appName}?`}
          confirmLabel="Revoke credential" busyText="Revoking…" tone="danger"
          onCancel={() => setDialog(null)}
          onConfirm={async () => {
            await revokeCredential(dialog.c.id);
            setDialog(null);
            flash(`Credential "${dialog.c.appName}" was revoked. Its key no longer works.`);
            load();
          }}
        >
          <p>The key <code>{dialog.c.keyPrefix}…</code> will be rejected from now on. Revocation is permanent; create a new credential if access is needed again.</p>
        </ConfirmDialog>
      )}
      {dialog?.type === 'view' && <Details detail={dialog.detail} onClose={() => setDialog(null)} />}
    </>
  );
}

function CreateCredential({ onCancel, onCreated }) {
  const [tenants, setTenants] = useState(null);
  const [templates, setTemplates] = useState(null);
  const [loadError, setLoadError] = useState('');
  const [v, setV] = useState({ appName: '', description: '', tenantId: '', templateIds: [] });
  const [touched, setTouched] = useState({});
  const [submitted, setSubmitted] = useState(false);
  const [serverError, setServerError] = useState(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    Promise.all([listTenants('', 'active'), listTemplates()])
      .then(([t, tp]) => { setTenants(t); setTemplates(tp.filter((x) => x.issuable)); })
      .catch((e) => setLoadError(e.message));
  }, []);

  const errors = {};
  const name = v.appName.trim();
  if (!name) errors.appName = 'Enter the application name.';
  else if (name.length < 3 || name.length > 100 || !NAME_RULE.test(name)) errors.appName = 'Use 3–100 letters, digits, spaces and . , \' ( ) & / _ -';
  if (v.description.length > 500) errors.description = 'Use at most 500 characters.';
  if (!v.tenantId) errors.tenantId = 'Choose the tenant this application issues IDs for.';
  if (v.templateIds.length === 0) errors.templateIds = 'Choose at least one template.';
  const fieldFor = (m) => (m.startsWith('tenantId') ? 'tenantId' : m.startsWith('templateIds') ? 'templateIds' : m.startsWith('appName') ? 'appName' : m.startsWith('description') ? 'description' : null);
  const err = (k) => ((submitted || touched[k]) && errors[k]) || (serverError?.field === k ? serverError.message : null);
  const blur = (k) => () => setTouched((t) => ({ ...t, [k]: true }));
  const toggle = (id) => setV({ ...v, templateIds: v.templateIds.includes(id) ? v.templateIds.filter((x) => x !== id) : [...v.templateIds, id] });

  async function submit(e) {
    e.preventDefault();
    setSubmitted(true);
    setServerError(null);
    if (Object.keys(errors).length) return;
    setBusy(true);
    try {
      onCreated(await createCredential({ ...v, appName: name, description: v.description.trim() }));
    } catch (ex) {
      setServerError({ field: fieldFor(ex.message), message: ex.message });
      setBusy(false);
    }
  }

  return (
    <Modal
      title="Create API credential" size="lg" onClose={busy ? () => {} : onCancel}
      footer={(
        <>
          <Button variant="secondary" onClick={onCancel} disabled={busy}>Cancel</Button>
          <Button type="submit" form="credential-create" busy={busy} busyText="Generating key…" disabled={!tenants}>Create and generate key</Button>
        </>
      )}
    >
      {loadError && <Alert kind="error">{loadError}</Alert>}
      {!tenants || !templates ? <LoadingRow /> : (
        <form id="credential-create" className="form" onSubmit={submit} noValidate>
          {serverError && !serverError.field && <Alert kind="error">{serverError.message}</Alert>}
          <Field label="Application name" required error={err('appName')} hint="Shown to administrators, e.g. Bengaluru revenue portal.">
            <input value={v.appName} onChange={(e) => setV({ ...v, appName: e.target.value })} onBlur={blur('appName')} maxLength={100} data-autofocus />
          </Field>
          <Field label="Description" error={err('description')} hint="Optional: owner, purpose, contact.">
            <textarea value={v.description} onChange={(e) => setV({ ...v, description: e.target.value })} onBlur={blur('description')} maxLength={500} />
          </Field>
          <Field label="Tenant" required error={err('tenantId')} hint="The key can issue IDs only for this tenant.">
            <select value={v.tenantId} onChange={(e) => setV({ ...v, tenantId: e.target.value })} onBlur={blur('tenantId')}>
              <option value="">Choose a tenant…</option>
              {[...tenants].sort((a, b) => a.cityName.localeCompare(b.cityName)).map((t) => (
                <option key={t.tenantId} value={t.tenantId}>{t.cityName} ({t.cityCode}) · {t.tenantId}</option>
              ))}
            </select>
          </Field>
          <fieldset className={`field ${err('templateIds') ? 'has-error' : ''}`} style={{ border: 0, padding: 0, margin: 0 }}
                    aria-describedby={err('templateIds') ? 'tpl-error' : undefined}>
            <legend className="field-label">Permitted templates <span className="req" aria-hidden="true">*</span></legend>
            <div className="check-list">
              {templates.map((t) => (
                <label key={t.templateId}>
                  <input type="checkbox" checked={v.templateIds.includes(t.templateId)} onChange={() => toggle(t.templateId)} />
                  <span><code>{t.templateId}</code></span>
                </label>
              ))}
            </div>
            <p className="field-hint">The key is rejected (403) for any other template.</p>
            {err('templateIds') && <p id="tpl-error" className="field-error">{err('templateIds')}</p>}
          </fieldset>
        </form>
      )}
    </Modal>
  );
}

/** Shows the full key exactly once. It cannot be dismissed by Esc or a backdrop click before the admin confirms. */
function KeyReveal({ issued, title, onDone }) {
  const [stored, setStored] = useState(false);
  const c = issued.credential;
  return (
    <Modal
      title={title} size="lg" onClose={() => { if (stored) onDone(); }}
      footer={<Button onClick={onDone} disabled={!stored}>Done</Button>}
    >
      <div className="form">
        <Alert kind="warning" title="Copy this key now">{issued.warning} Anyone with this key can issue IDs for {c.cityName} using the permitted templates.</Alert>
        <div className="field">
          <p className="field-label" id="key-label">API key for {c.appName}</p>
          <div className="key-box">
            <input readOnly value={issued.apiKey} onFocus={(e) => e.target.select()} aria-labelledby="key-label" data-autofocus />
            <CopyButton text={issued.apiKey} label="Copy key" describe="" variant="primary" size="md" />
          </div>
        </div>
        <div>
          <p className="field-label" style={{ marginBottom: 6 }}>Example request</p>
          <pre className="snippet">{`curl -X POST http://localhost:8080/api/v1/ids/issue \\
  -H "X-API-Key: $EGID_API_KEY" -H "Content-Type: application/json" \\
  -d '{"templateId":"${c.templates[0]}","city":"${c.tenantId}"}'`}</pre>
        </div>
        <label className="check-row">
          <input type="checkbox" checked={stored} onChange={(e) => setStored(e.target.checked)} />
          <span>I have copied the key and stored it securely.</span>
        </label>
      </div>
    </Modal>
  );
}

function Details({ detail, onClose }) {
  const c = detail.credential;
  return (
    <Modal title={c.appName} size="lg" onClose={onClose} footer={<Button variant="secondary" onClick={onClose}>Close</Button>}>
      <dl className="details">
        <dt>Status</dt><dd><StatusBadge status={c.status} /></dd>
        <dt>Description</dt><dd>{c.description || '–'}</dd>
        <dt>Tenant</dt><dd>{c.cityName} <code>{c.tenantId}</code></dd>
        <dt>Templates</dt><dd><Templates list={c.templates} max={50} /></dd>
        <dt>Key</dt><dd><code>{c.keyPrefix}…</code> <span className="cell-sub">(the full key is never shown again)</span></dd>
        <dt>Credential ID</dt><dd><code>{c.id}</code></dd>
        <dt>Created</dt><dd>{formatDate(c.createdAt, true)} by {c.createdBy}</dd>
        <dt>Last used</dt><dd>{c.lastUsedAt ? formatDate(c.lastUsedAt, true) : 'Never'}</dd>
        <dt>Last rotated</dt><dd>{c.rotatedAt ? formatDate(c.rotatedAt, true) : '–'}</dd>
        {c.revokedAt && (<><dt>Revoked</dt><dd>{formatDate(c.revokedAt, true)} by {c.revokedBy}</dd></>)}
      </dl>
      <h3 style={{ margin: '20px 0 10px', fontSize: '1rem' }}>History</h3>
      <ul className="audit-list">
        {detail.audit.map((a, i) => (
          <li key={i}><b>{a.action.toLowerCase()}</b> by {a.actor} · <span className="cell-sub">{formatDate(a.occurredAt, true)}</span>{a.details && <div className="cell-sub">{a.details}</div>}</li>
        ))}
      </ul>
    </Modal>
  );
}

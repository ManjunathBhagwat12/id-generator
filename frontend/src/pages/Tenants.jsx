import { useCallback, useEffect, useRef, useState } from 'react';
import {
  createTenant, getTenant, listTenants, setTenantActive, updateTenant,
} from '../api.js';
import TenantForm from '../components/TenantForm.jsx';
import {
  Alert, Badge, Button, Card, ConfirmDialog, EmptyState, LoadingRow, Modal, PageHeader, formatDate,
} from '../components/ui.jsx';

export default function Tenants() {
  const [tenants, setTenants] = useState([]);
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState('all');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [opening, setOpening] = useState(''); // tenantId whose details are loading
  const [dialog, setDialog] = useState(null); // {type:'add'} | {type:'edit'|'view'|'toggle', tenant, detail?}
  const noticeTimer = useRef();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setTenants(await listTenants(search, status));
      setError('');
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [search, status]);

  // debounce the search box
  useEffect(() => {
    const t = setTimeout(load, 250);
    return () => clearTimeout(t);
  }, [load]);
  useEffect(() => () => clearTimeout(noticeTimer.current), []);

  const flash = (m) => {
    setNotice(m);
    clearTimeout(noticeTimer.current);
    noticeTimer.current = setTimeout(() => setNotice(''), 6000);
  };

  async function open(type, t) {
    setOpening(`${type}:${t.tenantId}`);
    try {
      setDialog({ type, tenant: t, detail: await getTenant(t.tenantId) });
    } catch (e) {
      setError(e.message);
    } finally {
      setOpening('');
    }
  }

  const filtered = search.trim() || status !== 'all';

  return (
    <>
      <PageHeader
        title="Tenant management"
        description="Cities that can issue IDs. Tenants are never deleted; deactivate a tenant to stop it issuing IDs."
        actions={<Button onClick={() => setDialog({ type: 'add' })}>Add tenant</Button>}
      />

      {error && <Alert kind="error" onClose={() => setError('')}>{error}</Alert>}
      {notice && <Alert kind="success" onClose={() => setNotice('')}>{notice}</Alert>}

      <Card className="flush">
        <div className="toolbar" role="search">
          <div className="search">
            <input type="search" placeholder="Search by tenant ID, city code or city name" value={search}
                   onChange={(e) => setSearch(e.target.value)} aria-label="Search tenants" />
          </div>
          <select value={status} onChange={(e) => setStatus(e.target.value)} aria-label="Filter by status">
            <option value="all">All statuses</option>
            <option value="active">Active only</option>
            <option value="inactive">Inactive only</option>
          </select>
        </div>

        {loading && tenants.length === 0 ? <LoadingRow label="Loading tenants…" /> : tenants.length === 0 ? (
          <EmptyState title={filtered ? 'No tenants match your search' : 'No tenants yet'}>
            {filtered ? 'Try a different search term or status filter.' : 'Add the first tenant to start issuing IDs.'}
          </EmptyState>
        ) : (
          <>
            <div className="table-wrap">
              <table aria-busy={loading}>
                <caption className="sr-only">Tenants</caption>
                <thead>
                  <tr>
                    <th scope="col">City</th>
                    <th scope="col">Tenant ID</th>
                    <th scope="col">Code</th>
                    <th scope="col">Status</th>
                    <th scope="col" className="hide-sm">Last updated</th>
                    <th scope="col" className="actions"><span className="sr-only">Actions</span></th>
                  </tr>
                </thead>
                <tbody>
                  {tenants.map((t) => (
                    <tr key={t.tenantId} className={t.active ? '' : 'is-inactive'}>
                      <td className="cell-strong">{t.cityName}</td>
                      <td><code>{t.tenantId}</code></td>
                      <td><code>{t.cityCode}</code></td>
                      <td><Badge tone={t.active ? 'success' : 'neutral'}>{t.active ? 'Active' : 'Inactive'}</Badge></td>
                      <td className="cell-sub hide-sm">{formatDate(t.updatedAt)}</td>
                      <td className="actions">
                        <Button variant="ghost" size="sm" onClick={() => open('view', t)} busy={opening === `view:${t.tenantId}`}
                                aria-label={`View ${t.cityName}`}>View</Button>
                        <Button variant="ghost" size="sm" onClick={() => open('edit', t)} busy={opening === `edit:${t.tenantId}`}
                                aria-label={`Edit ${t.cityName}`}>Edit</Button>
                        <Button variant="ghost" size="sm" onClick={() => setDialog({ type: 'toggle', tenant: t })}
                                aria-label={`${t.active ? 'Deactivate' : 'Activate'} ${t.cityName}`}>
                          {t.active ? 'Deactivate' : 'Activate'}
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="table-foot" aria-live="polite">
              {tenants.length} {tenants.length === 1 ? 'tenant' : 'tenants'}{filtered ? ' match' : ''}
            </div>
          </>
        )}
      </Card>

      {dialog?.type === 'add' && (
        <TenantForm
          onCancel={() => setDialog(null)}
          onSubmit={async (v) => {
            const t = await createTenant(v);
            setDialog(null);
            flash(`Tenant ${t.tenantId} (${t.cityName}) was added and can issue IDs now.`);
            load();
          }}
        />
      )}
      {dialog?.type === 'edit' && (
        <TenantForm
          tenant={dialog.tenant} locked={dialog.detail.cityCodeLocked}
          onCancel={() => setDialog(null)}
          onSubmit={async (v) => {
            await updateTenant(dialog.tenant.tenantId, { cityCode: v.cityCode, cityName: v.cityName });
            setDialog(null);
            flash(`Tenant ${dialog.tenant.tenantId} was updated.`);
            load();
          }}
        />
      )}
      {dialog?.type === 'toggle' && (
        <ConfirmDialog
          title={`${dialog.tenant.active ? 'Deactivate' : 'Activate'} ${dialog.tenant.cityName}?`}
          confirmLabel={dialog.tenant.active ? 'Deactivate tenant' : 'Activate tenant'}
          busyText={dialog.tenant.active ? 'Deactivating…' : 'Activating…'}
          tone={dialog.tenant.active ? 'danger' : 'primary'}
          onCancel={() => setDialog(null)}
          onConfirm={async () => {
            const t = dialog.tenant;
            await setTenantActive(t.tenantId, !t.active);
            setDialog(null);
            flash(`${t.cityName} was ${t.active ? 'deactivated' : 'activated'}.`);
            load();
          }}
        >
          <p>
            {dialog.tenant.active
              ? <>New ID requests for <b>{dialog.tenant.tenantId}</b> will be rejected and the city disappears from the generator. Already issued IDs and counters are kept; you can reactivate it at any time.</>
              : <><b>{dialog.tenant.tenantId}</b> will be able to issue IDs again. Its counters continue where they stopped.</>}
          </p>
        </ConfirmDialog>
      )}
      {dialog?.type === 'view' && (
        <Modal title={dialog.detail.cityName} onClose={() => setDialog(null)}
               footer={<Button variant="secondary" onClick={() => setDialog(null)}>Close</Button>}>
          <dl className="details">
            <dt>Tenant ID</dt><dd><code>{dialog.detail.tenantId}</code></dd>
            <dt>City code</dt><dd><code>{dialog.detail.cityCode}</code>{dialog.detail.cityCodeLocked && <span className="cell-sub"> · locked (IDs issued)</span>}</dd>
            <dt>City name</dt><dd>{dialog.detail.cityName}</dd>
            <dt>Status</dt><dd><Badge tone={dialog.detail.active ? 'success' : 'neutral'}>{dialog.detail.active ? 'Active' : 'Inactive'}</Badge></dd>
            <dt>IDs issued</dt><dd>{dialog.detail.issuedIdCount.toLocaleString()}</dd>
            <dt>Created</dt><dd>{formatDate(dialog.detail.createdAt, true)}</dd>
            <dt>Last updated</dt><dd>{formatDate(dialog.detail.updatedAt, true)}</dd>
          </dl>
        </Modal>
      )}
    </>
  );
}

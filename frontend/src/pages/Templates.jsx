import { useCallback, useEffect, useRef, useState } from 'react';
import { getTemplate, getTemplates, listTemplates } from '../api.js';
import TemplateForm from '../components/TemplateForms.jsx';
import {
  Alert, Badge, Button, Card, CopyButton, EmptyState, FormatExpression, LoadingRow, Modal, PageHeader, formatDate,
} from '../components/ui.jsx';

export default function Templates() {
  const [templates, setTemplates] = useState([]);
  const [scopes, setScopes] = useState({}); // templateId -> counter scope description (public API)
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [opening, setOpening] = useState('');
  const [dialog, setDialog] = useState(null); // {type:'create'} | {type:'view', detail}
  const noticeTimer = useRef();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [admin, pub] = await Promise.all([listTemplates(), getTemplates()]);
      setTemplates(admin);
      setScopes(Object.fromEntries(pub.map((t) => [t.id, t.counterScope])));
      setError('');
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => { load(); }, [load]);
  useEffect(() => () => clearTimeout(noticeTimer.current), []);

  async function view(id) {
    setOpening(id);
    try {
      setDialog({ type: 'view', detail: await getTemplate(id) });
    } catch (e) {
      setError(e.message);
    } finally {
      setOpening('');
    }
  }

  const ready = templates.filter((t) => t.issuable).length;

  return (
    <>
      <PageHeader
        title="Template management"
        description="Each template has one immutable format. Templates cannot be edited or deleted; use a new template ID for a different format."
        actions={<Button onClick={() => setDialog({ type: 'create' })}>Create template</Button>}
      />

      {error && <Alert kind="error" onClose={() => setError('')}>{error}</Alert>}
      {notice && <Alert kind="success" onClose={() => setNotice('')}>{notice}</Alert>}

      <Card
        className="flush"
        title={loading ? 'Templates' : `Templates (${templates.length})`}
        actions={(
          <div className="legend" aria-label="Placeholder colours">
            <span><span className="fx-tok fx-city">CITY</span> city</span>
            <span><span className="fx-tok fx-date">FY / CY</span> date</span>
            <span><span className="fx-tok fx-seq">SEQ</span> sequence</span>
          </div>
        )}
      >
        {loading && templates.length === 0 ? <LoadingRow label="Loading templates…" /> : templates.length === 0 ? (
          <EmptyState title="No templates">Create the first template to start issuing IDs.</EmptyState>
        ) : (
          <>
            <div className="table-wrap" style={{ marginTop: 12 }}>
              <table aria-busy={loading}>
                <caption className="sr-only">Templates</caption>
                <thead>
                  <tr>
                    <th scope="col">Template</th>
                    <th scope="col">Format</th>
                    <th scope="col" className="hide-sm">Counter</th>
                    <th scope="col">Status</th>
                    <th scope="col" className="actions"><span className="sr-only">Actions</span></th>
                  </tr>
                </thead>
                <tbody>
                  {templates.map((t) => (
                    <tr key={t.templateId}>
                      <td>
                        <div className="cell-strong"><code>{t.templateId}</code></div>
                        <div className="cell-sub">{t.name}</div>
                      </td>
                      <td><FormatExpression format={t.format} /></td>
                      <td className="cell-sub hide-sm">{scopes[t.templateId] ?? '–'}</td>
                      <td><Badge tone={t.issuable ? 'success' : 'warning'}>{t.issuable ? 'Ready to issue' : 'Not supported'}</Badge></td>
                      <td className="actions">
                        <Button variant="ghost" size="sm" onClick={() => view(t.templateId)} busy={opening === t.templateId}
                                aria-label={`View ${t.templateId}`}>View</Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="table-foot">{ready} of {templates.length} templates ready to issue IDs</div>
          </>
        )}
      </Card>

      {dialog?.type === 'create' && (
        <TemplateForm
          onCancel={() => setDialog(null)}
          onDone={(id) => {
            setDialog(null);
            setNotice(`Template ${id} was created and can be used for ID generation now.`);
            clearTimeout(noticeTimer.current);
            noticeTimer.current = setTimeout(() => setNotice(''), 6000);
            load();
          }}
        />
      )}
      {dialog?.type === 'view' && (
        <Modal title={dialog.detail.templateId} size="lg" onClose={() => setDialog(null)}
               footer={<Button variant="secondary" onClick={() => setDialog(null)}>Close</Button>}>
          <dl className="details">
            <dt>Name</dt><dd>{dialog.detail.name}</dd>
            <dt>Format</dt>
            <dd style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
              <FormatExpression format={dialog.detail.format} />
              <CopyButton text={dialog.detail.format} describe="format" />
            </dd>
            <dt>Counter</dt><dd>{scopes[dialog.detail.templateId] ?? '–'}</dd>
            <dt>Status</dt>
            <dd><Badge tone={dialog.detail.issuable ? 'success' : 'warning'}>{dialog.detail.issuable ? 'Ready to issue' : 'Not supported'}</Badge></dd>
            <dt>IDs issued</dt><dd>{dialog.detail.issuedIdCount.toLocaleString()}</dd>
            <dt>Created</dt><dd>{formatDate(dialog.detail.createdAt, true)} by {dialog.detail.createdBy}</dd>
          </dl>
        </Modal>
      )}
    </>
  );
}

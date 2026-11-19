import { useRef, useState } from 'react';
import { createTemplate } from '../api.js';
import { Alert, Button, Field, FormatExpression, Modal } from './ui.jsx';

const ID_RULE = /^[A-Z][A-Z0-9_]{2,49}$/;
const TOKEN = /\[([A-Z][A-Z_]*)(?::([^\][]*))?\]/g;

/** Placeholders offered as insert buttons; the server validates the final format. */
const SNIPPETS = [
  { text: '[CITY]', help: 'city code' },
  { text: '[FY:yyyy-yy]', help: 'financial year 2026-27' },
  { text: '[FY:yy-yy]', help: 'financial year 26-27' },
  { text: '[CY:yyyy]', help: 'year 2026' },
  { text: '[CY:MM-yyyy]', help: 'month-year 11-2026' },
  { text: '[SEQ:6]', help: 'own counter, 6 digits' },
];

function validate(v) {
  const e = {};
  const id = v.templateId.trim().toUpperCase();
  if (!id) e.templateId = 'Enter a template ID.';
  else if (!ID_RULE.test(id)) e.templateId = '3–50 capital letters, digits or _, starting with a letter.';
  const name = v.name.trim();
  if (!name) e.name = 'Enter a name.';
  else if (name.length < 3) e.name = 'Use at least 3 characters.';
  const f = v.format.trim();
  if (!f) e.format = 'Enter a format.';
  else if (f.length > 200) e.format = 'Use at most 200 characters.';
  else {
    const seqs = [...f.matchAll(TOKEN)].filter((m) => m[1] === 'SEQ' || m[1].startsWith('SEQ_')).length;
    const stray = f.replace(TOKEN, '');
    if (/[[\]]/.test(stray)) e.format = 'A placeholder is not closed or not written in capitals, e.g. [CITY].';
    else if (seqs !== 1) e.format = 'Include exactly one sequence placeholder, e.g. [SEQ:6] or [SEQ_WS:4].';
  }
  return e;
}

/** Map a server message to the field it is about. */
function fieldFor(message) {
  const m = message.toLowerCase();
  if (m.startsWith('invalid template format') || m.includes('this format') || m.startsWith('format ')) return 'format';
  if (m.includes('already exists') || m.startsWith('templateid')) return 'templateId';
  if (m.startsWith('name ')) return 'name';
  return null;
}

/** Create a new template. Templates are immutable: there is no edit form. */
export default function TemplateForm({ onDone, onCancel }) {
  const [v, setV] = useState({ templateId: '', name: '', format: '' });
  const [touched, setTouched] = useState({});
  const [submitted, setSubmitted] = useState(false);
  const [serverError, setServerError] = useState(null);
  const [busy, setBusy] = useState(false);
  const formatRef = useRef(null);
  const errors = validate(v);
  const fieldError = (k) => ((submitted || touched[k]) && errors[k]) || (serverError?.field === k ? serverError.message : null);
  const bind = (k) => ({
    value: v[k],
    onChange: (e) => { setV({ ...v, [k]: e.target.value }); if (serverError?.field === k) setServerError(null); },
    onBlur: () => setTouched((t) => ({ ...t, [k]: true })),
  });

  function insert(text) {
    const el = formatRef.current;
    const start = el?.selectionStart ?? v.format.length;
    const end = el?.selectionEnd ?? v.format.length;
    const next = v.format.slice(0, start) + text + v.format.slice(end);
    setV({ ...v, format: next });
    requestAnimationFrame(() => {
      el?.focus();
      el?.setSelectionRange(start + text.length, start + text.length);
    });
  }

  async function submit(e) {
    e.preventDefault();
    setSubmitted(true);
    setServerError(null);
    if (Object.keys(errors).length) return;
    setBusy(true);
    try {
      const t = await createTemplate({ templateId: v.templateId.trim(), name: v.name.trim(), format: v.format.trim() });
      onDone(t.templateId);
    } catch (err) {
      setServerError({ field: fieldFor(err.message), message: err.message });
      setBusy(false);
    }
  }

  return (
    <Modal
      title="Create template"
      size="lg"
      onClose={busy ? () => {} : onCancel}
      footer={(
        <>
          <Button variant="secondary" onClick={onCancel} disabled={busy}>Cancel</Button>
          <Button type="submit" form="template-create" busy={busy} busyText="Creating…">Create template</Button>
        </>
      )}
    >
      <form id="template-create" onSubmit={submit} className="form" noValidate>
        <Alert kind="info">
          A template cannot be edited or deleted after it is created. For a different format later, create a new template with a new ID.
        </Alert>
        {serverError && !serverError.field && <Alert kind="error">{serverError.message}</Alert>}
        <div className="form-row">
          <Field label="Template ID" required error={fieldError('templateId')} hint="Capital letters, digits and _, e.g. WP_REGISTER.">
            <input {...bind('templateId')} className="input-mono" placeholder="WP_REGISTER" autoComplete="off"
                   style={{ textTransform: 'uppercase' }} data-autofocus />
          </Field>
          <Field label="Name" required error={fieldError('name')}>
            <input {...bind('name')} placeholder="Water point register" autoComplete="off" />
          </Field>
        </div>
        <Field label="Format" required error={fieldError('format')}
               hint="Literal text may use letters, digits and / _ . -. Named counters such as [SEQ_WS:4] are shared by templates using the same name. Without [CITY], one counter is shared by all cities.">
          <input {...bind('format')} ref={formatRef} className="input-mono" placeholder="WP-[CITY]-[SEQ_WS:8]-[CY:MM-yyyy]" autoComplete="off" />
        </Field>
        <div>
          <p className="field-label" id="snippets-label" style={{ marginBottom: 6 }}>Insert a placeholder</p>
          <div className="chips" role="group" aria-labelledby="snippets-label">
            {SNIPPETS.map((s) => (
              <button key={s.text} type="button" className="chip" onClick={() => insert(s.text)} title={s.help}
                      aria-label={`Insert ${s.text}, ${s.help}`}>{s.text}</button>
            ))}
          </div>
        </div>
        {v.format.trim() && (
          <div className="template-info">
            <div className="row"><span className="k">Preview</span><FormatExpression format={v.format.trim()} /></div>
            <div className="legend" aria-hidden="true">
              <span><span className="fx-tok fx-city">CITY</span> city</span>
              <span><span className="fx-tok fx-date">FY / CY</span> date</span>
              <span><span className="fx-tok fx-seq">SEQ</span> sequence</span>
            </div>
          </div>
        )}
      </form>
    </Modal>
  );
}

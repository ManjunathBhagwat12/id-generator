import { Children, cloneElement, isValidElement, useEffect, useId, useRef, useState } from 'react';

/*
 * Small shared UI kit: one consistent look for buttons, form fields, alerts, badges, dialogs and copy actions.
 * No external UI library; accessibility (labels, aria-*, focus handling, keyboard) is built in here once.
 */

export function Spinner({ label }) {
  return <span className="spinner" role={label ? 'status' : undefined} aria-label={label} aria-hidden={label ? undefined : true} />;
}

/**
 * variant: primary | secondary | ghost | danger. `busy` disables the button and shows `busyText`
 * (prevents double submission).
 */
export function Button({ variant = 'primary', size, busy = false, busyText, disabled, className = '', children, type = 'button', ...rest }) {
  return (
    <button
      type={type}
      className={`btn btn-${variant}${size ? ` btn-${size}` : ''} ${className}`}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      {...rest}
    >
      {busy && <Spinner />}
      <span>{busy && busyText ? busyText : children}</span>
    </button>
  );
}

/** Label + control + hint + error, wired together for screen readers. The single child is the control. */
export function Field({ label, hint, error, required, children, className = '' }) {
  const id = useId();
  const hintId = hint ? `${id}-hint` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  const control = Children.only(children);
  const described = [hintId, errorId].filter(Boolean).join(' ') || undefined;
  return (
    <div className={`field ${error ? 'has-error' : ''} ${className}`}>
      <label htmlFor={id} className="field-label">
        {label}
        {required && <span className="req" aria-hidden="true"> *</span>}
      </label>
      {isValidElement(control)
        ? cloneElement(control, { id, 'aria-describedby': described, 'aria-invalid': error ? true : undefined, 'aria-required': required || undefined })
        : control}
      {hint && <p id={hintId} className="field-hint">{hint}</p>}
      {error && <p id={errorId} className="field-error">{error}</p>}
    </div>
  );
}

/** kind: error | success | info | warning. Errors are announced immediately, the rest politely. */
export function Alert({ kind = 'info', title, children, onClose }) {
  return (
    <div className={`alert alert-${kind}`} role={kind === 'error' ? 'alert' : 'status'}>
      <div className="alert-body">
        {title && <strong className="alert-title">{title}</strong>}
        <div>{children}</div>
      </div>
      {onClose && (
        <button type="button" className="icon-btn" onClick={onClose} aria-label="Dismiss message">×</button>
      )}
    </div>
  );
}

/** tone: success | neutral | warning | danger | info */
export function Badge({ tone = 'neutral', children }) {
  return <span className={`badge badge-${tone}`}><span className="badge-dot" aria-hidden="true" />{children}</span>;
}

export function PageHeader({ title, description, actions }) {
  return (
    <div className="page-header">
      <div>
        <h1>{title}</h1>
        {description && <p className="page-desc">{description}</p>}
      </div>
      {actions && <div className="page-actions">{actions}</div>}
    </div>
  );
}

export function Card({ title, actions, children, className = '', ...rest }) {
  return (
    <section className={`card ${className}`} {...rest}>
      {(title || actions) && (
        <div className="card-head">
          {title && <h2>{title}</h2>}
          {actions && <div className="card-actions">{actions}</div>}
        </div>
      )}
      {children}
    </section>
  );
}

export function EmptyState({ title, children }) {
  return (
    <div className="empty">
      <p className="empty-title">{title}</p>
      {children && <p className="muted">{children}</p>}
    </div>
  );
}

export function LoadingRow({ label = 'Loading…' }) {
  return <div className="loading-row"><Spinner /> <span>{label}</span></div>;
}

// ---------------------------------------------------------------- copy

async function copyText(text) {
  if (navigator.clipboard && window.isSecureContext) {
    await navigator.clipboard.writeText(text);
    return;
  }
  // Fallback for non-secure origins (e.g. opened via a LAN IP).
  const ta = document.createElement('textarea');
  ta.value = text;
  ta.setAttribute('readonly', '');
  ta.style.position = 'fixed';
  ta.style.opacity = '0';
  document.body.appendChild(ta);
  ta.select();
  const ok = document.execCommand('copy');
  document.body.removeChild(ta);
  if (!ok) throw new Error('copy failed');
}

/** Copies `text`; the button itself confirms ("Copied") and a polite live region announces it. */
export function CopyButton({ text, label = 'Copy', describe, size = 'sm', variant = 'ghost' }) {
  const [state, setState] = useState('idle'); // idle | done | fail
  const timer = useRef();
  useEffect(() => () => clearTimeout(timer.current), []);
  async function copy() {
    try {
      await copyText(text);
      setState('done');
    } catch {
      setState('fail');
    }
    clearTimeout(timer.current);
    timer.current = setTimeout(() => setState('idle'), 1600);
  }
  return (
    <button
      type="button"
      className={`btn btn-${variant} btn-${size} copy-btn ${state}`}
      onClick={copy}
      aria-label={describe ? `${label} ${describe}` : label}
    >
      <span aria-hidden="true">{state === 'done' ? 'Copied' : state === 'fail' ? 'Copy failed' : label}</span>
      <span className="sr-only" aria-live="polite">{state === 'done' ? 'Copied to clipboard' : state === 'fail' ? 'Copy failed' : ''}</span>
    </button>
  );
}

// ---------------------------------------------------------------- dialogs

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/** Accessible modal: Esc closes, Tab stays inside, focus returns to the opener on close. */
export function Modal({ title, onClose, children, size = 'md', footer }) {
  const ref = useRef(null);
  const titleId = useId();
  useEffect(() => {
    const opener = document.activeElement;
    const node = ref.current;
    // Prefer an explicit [data-autofocus], then the first control in the body (not the close button).
    const auto = node.querySelector('[data-autofocus]') || node.querySelector('.modal-body')?.querySelector(FOCUSABLE)
      || node.querySelector(FOCUSABLE);
    (auto || node).focus();
    const onKey = (e) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        onClose();
      } else if (e.key === 'Tab') {
        const items = [...node.querySelectorAll(FOCUSABLE)];
        if (items.length === 0) return;
        const first = items[0];
        const last = items[items.length - 1];
        if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
        else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
      }
    };
    node.addEventListener('keydown', onKey);
    document.body.classList.add('modal-open');
    return () => {
      node.removeEventListener('keydown', onKey);
      document.body.classList.remove('modal-open');
      if (opener && opener.focus) opener.focus();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return (
    <div className="backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div ref={ref} className={`modal modal-${size}`} role="dialog" aria-modal="true" aria-labelledby={titleId} tabIndex={-1}>
        <div className="modal-head">
          <h2 id={titleId}>{title}</h2>
          <button type="button" className="icon-btn" onClick={onClose} aria-label="Close dialog">×</button>
        </div>
        <div className="modal-body">{children}</div>
        {footer && <div className="modal-foot">{footer}</div>}
      </div>
    </div>
  );
}

/** Replaces window.confirm with an accessible, styled confirmation. onConfirm may be async. */
export function ConfirmDialog({ title, children, confirmLabel, busyText, tone = 'primary', onConfirm, onCancel }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  async function confirm() {
    setBusy(true);
    setError('');
    try {
      await onConfirm();
    } catch (e) {
      setError(e.message);
      setBusy(false);
    }
  }
  return (
    <Modal
      title={title}
      onClose={busy ? () => {} : onCancel}
      size="sm"
      footer={(
        <>
          <Button variant="secondary" onClick={onCancel} disabled={busy}>Cancel</Button>
          <Button variant={tone} onClick={confirm} busy={busy} busyText={busyText} data-autofocus>{confirmLabel}</Button>
        </>
      )}
    >
      {error && <Alert kind="error">{error}</Alert>}
      {children}
    </Modal>
  );
}

// ---------------------------------------------------------------- template expressions

const TOKEN = /\[([A-Z][A-Z_]*)(?::([^\][]*))?\]/g;
const KIND_INFO = {
  CITY: ['city', 'City code of the tenant'],
  FY: ['date', 'Indian financial year (April–March)'],
  CY: ['date', 'Calendar date'],
  SEQ: ['seq', 'Sequence number'],
};

/** Renders a template format with its placeholders highlighted and explained on hover/focus. */
export function FormatExpression({ format }) {
  const parts = [];
  let last = 0;
  for (const m of format.matchAll(TOKEN)) {
    if (m.index > last) parts.push(<span key={`l${last}`} className="fx-lit">{format.slice(last, m.index)}</span>);
    const name = m[1];
    const kind = name === 'SEQ' || name.startsWith('SEQ_') ? 'SEQ' : name;
    const [cls, desc] = KIND_INFO[kind] ?? ['unknown', 'Unsupported placeholder'];
    const detail = kind === 'SEQ'
      ? `${name === 'SEQ' ? 'Sequence' : `Named counter ${name}`}, ${m[2]} digits`
      : m[2] ? `${desc}, pattern ${m[2]}` : desc;
    parts.push(<span key={`t${m.index}`} className={`fx-tok fx-${cls}`} title={detail}>{m[0]}</span>);
    last = m.index + m[0].length;
  }
  if (last < format.length) parts.push(<span key="end" className="fx-lit">{format.slice(last)}</span>);
  // No aria-label: the visible text IS the format (aria-label on a generic element is not allowed by ARIA).
  return <code className="fx">{parts}</code>;
}

export function formatDate(value, withTime = false) {
  if (!value) return '–';
  const d = new Date(value);
  return withTime
    ? d.toLocaleString(undefined, { day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' })
    : d.toLocaleDateString(undefined, { day: '2-digit', month: 'short', year: 'numeric' });
}

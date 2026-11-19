import { useState } from 'react';
import { Alert, Button, Field, Modal } from './ui.jsx';

export { Modal } from './ui.jsx';

// Mirrors the backend rules for fast feedback; the server remains the authority.
const TENANT_ID = /^[a-z0-9]+(\.[a-z0-9_-]+)+$/;
const CITY_CODE = /^[A-Z0-9]{2,10}$/;
const CITY_NAME = /^[\p{L}\p{M}0-9][\p{L}\p{M}0-9 .,'()&/-]*$/u;

export function validate(v, creating) {
  const e = {};
  if (creating) {
    if (!v.tenantId.trim()) e.tenantId = 'Enter a tenant ID.';
    else if (!TENANT_ID.test(v.tenantId.trim().toLowerCase()))
      e.tenantId = 'Use lowercase letters and digits with at least one dot, e.g. tn.coimbatore.';
  }
  if (!v.cityCode.trim()) e.cityCode = 'Enter a city code.';
  else if (!CITY_CODE.test(v.cityCode.trim().toUpperCase())) e.cityCode = 'Use 2–10 letters or digits, e.g. CBE.';
  const name = v.cityName.trim();
  if (!name) e.cityName = 'Enter the city name.';
  else if (name.length < 2 || name.length > 200 || !CITY_NAME.test(name))
    e.cityName = 'Use 2–200 letters, digits, spaces and . , \' ( ) & / -';
  return e;
}

/** Puts a server error next to the field it is about, when it clearly names one. */
function fieldForServerError(message) {
  const m = message.toLowerCase();
  if (m.includes('city code') || m.startsWith('citycode')) return 'cityCode';
  if (m.includes('tenant id') || m.startsWith('tenantid')) return 'tenantId';
  if (m.startsWith('cityname')) return 'cityName';
  return null;
}

/** Create (no tenant) or edit (tenant given) form shown in a modal. */
export default function TenantForm({ tenant, locked, onSubmit, onCancel }) {
  const creating = !tenant;
  const [v, setV] = useState({
    tenantId: tenant?.tenantId ?? '', cityCode: tenant?.cityCode ?? '', cityName: tenant?.cityName ?? '',
  });
  const [touched, setTouched] = useState({});
  const [submitted, setSubmitted] = useState(false);
  const [serverError, setServerError] = useState(null); // {field, message}
  const [busy, setBusy] = useState(false);
  const errors = validate(v, creating);
  const show = (k) => (submitted || touched[k]) && errors[k];
  const fieldError = (k) => show(k) || (serverError?.field === k ? serverError.message : null);
  const bind = (k) => ({
    value: v[k],
    onChange: (e) => { setV({ ...v, [k]: e.target.value }); if (serverError?.field === k) setServerError(null); },
    onBlur: () => setTouched((t) => ({ ...t, [k]: true })),
  });

  async function submit(e) {
    e.preventDefault();
    setSubmitted(true);
    setServerError(null);
    if (Object.keys(errors).length) return;
    setBusy(true);
    try {
      await onSubmit({ tenantId: v.tenantId.trim(), cityCode: v.cityCode.trim(), cityName: v.cityName.trim() });
    } catch (err) {
      setServerError({ field: fieldForServerError(err.message), message: err.message });
      setBusy(false);
    }
  }

  const formId = creating ? 'tenant-create' : 'tenant-edit';
  return (
    <Modal
      title={creating ? 'Add tenant' : `Edit tenant ${tenant.tenantId}`}
      onClose={busy ? () => {} : onCancel}
      footer={(
        <>
          <Button variant="secondary" onClick={onCancel} disabled={busy}>Cancel</Button>
          <Button type="submit" form={formId} busy={busy} busyText="Saving…">{creating ? 'Add tenant' : 'Save changes'}</Button>
        </>
      )}
    >
      <form id={formId} onSubmit={submit} className="form" noValidate>
        {serverError && !serverError.field && <Alert kind="error">{serverError.message}</Alert>}
        <Field label="Tenant ID" required={creating} error={creating ? fieldError('tenantId') : null}
               hint={creating ? 'Lowercase, with a state prefix, e.g. tn.coimbatore. Cannot be changed later.' : 'The tenant ID cannot be changed.'}>
          <input {...bind('tenantId')} disabled={!creating} placeholder="tn.coimbatore" className="input-mono"
                 autoComplete="off" data-autofocus={creating ? true : undefined} />
        </Field>
        <div className="form-row">
          <Field label="City code" required error={fieldError('cityCode')}
                 hint={locked ? 'Locked: this tenant has already issued IDs.' : '2–10 letters or digits; used inside IDs.'}>
            <input {...bind('cityCode')} disabled={locked} placeholder="CBE" className="input-mono" autoComplete="off"
                   style={{ textTransform: 'uppercase' }} />
          </Field>
          <Field label="City name" required error={fieldError('cityName')}>
            <input {...bind('cityName')} placeholder="Coimbatore" autoComplete="off"
                   data-autofocus={creating ? undefined : true} />
          </Field>
        </div>
      </form>
    </Modal>
  );
}

import { useState } from 'react';
import { useAuth } from '../auth.jsx';
import { Alert, Button, Field } from '../components/ui.jsx';

export default function Login() {
  const { login } = useAuth();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const userError = submitted && !username.trim() ? 'Enter your username.' : null;
  const passError = submitted && !password ? 'Enter your password.' : null;

  async function onSubmit(e) {
    e.preventDefault();
    setSubmitted(true);
    setError('');
    if (!username.trim() || !password) return;
    setBusy(true);
    try {
      await login(username.trim(), password);
      window.location.hash = '/admin';
    } catch (err) {
      setError(err.status === 401 ? 'The username or password is incorrect.' : err.message);
      setPassword('');
      setBusy(false);
    }
  }

  return (
    <div className="auth-wrap">
      <div className="card auth-card">
        <h1>Administrator sign in</h1>
        <p className="page-desc">Manage tenants and ID templates.</p>
        {error && <Alert kind="error" title="Sign in failed">{error}</Alert>}
        <form onSubmit={onSubmit} className="form" noValidate>
          <Field label="Username" required error={userError}>
            <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" autoFocus />
          </Field>
          <Field label="Password" required error={passError}>
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" />
          </Field>
          <Button type="submit" size="lg" className="btn-block" busy={busy} busyText="Signing in…">Sign in</Button>
        </form>
        <p className="auth-foot"><a href="#/">Back to the ID Generator</a></p>
      </div>
    </div>
  );
}

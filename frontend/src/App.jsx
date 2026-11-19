import { useEffect, useState } from 'react';
import { useAuth } from './auth.jsx';
import Generator from './pages/Generator.jsx';
import Login from './pages/Login.jsx';
import Dashboard from './pages/Dashboard.jsx';
import Tenants from './pages/Tenants.jsx';
import Templates from './pages/Templates.jsx';
import Credentials from './pages/Credentials.jsx';
import { Button, LoadingRow } from './components/ui.jsx';

// Minimal hash router: #/ (generator), #/login, #/admin, #/admin/tenants, #/admin/templates
function useRoute() {
  const [route, setRoute] = useState(window.location.hash.slice(1) || '/');
  useEffect(() => {
    const onChange = () => setRoute(window.location.hash.slice(1) || '/');
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);
  return route;
}

const TITLES = {
  '/': 'ID Generator',
  '/login': 'Admin login',
  '/admin': 'Admin dashboard',
  '/admin/tenants': 'Tenant management',
  '/admin/templates': 'Template management',
  '/admin/credentials': 'API credentials',
};

function NavLink({ href, route, children }) {
  return <a href={`#${href}`} aria-current={route === href ? 'page' : undefined}>{children}</a>;
}

export default function App() {
  const route = useRoute();
  const { user, ready, logout } = useAuth();
  const [loggingOut, setLoggingOut] = useState(false);
  const isAdminRoute = route.startsWith('/admin');

  // Admin screens are never rendered for anonymous users (the API enforces this independently).
  useEffect(() => {
    if (ready && isAdminRoute && !user) window.location.hash = '/login';
    if (ready && route === '/login' && user) window.location.hash = '/admin';
  }, [ready, isAdminRoute, user, route]);

  useEffect(() => {
    document.title = `${TITLES[route] ?? 'Page'} · Government ID Generator`;
  }, [route]);

  async function onLogout() {
    setLoggingOut(true);
    try {
      await logout();
      window.location.hash = '/';
    } finally {
      setLoggingOut(false);
    }
  }

  let page;
  if (!ready) page = <LoadingRow />;
  else if (route === '/login') page = <Login />;
  else if (isAdminRoute) {
    if (!user) page = null;
    else if (route === '/admin/tenants') page = <Tenants />;
    else if (route === '/admin/templates') page = <Templates />;
    else if (route === '/admin/credentials') page = <Credentials />;
    else page = <Dashboard />;
  } else page = <Generator />;

  return (
    <>
      <a className="skip-link" href="#main" onClick={(e) => { e.preventDefault(); document.getElementById('main')?.focus(); }}>
        Skip to main content
      </a>
      <header className="topbar">
        <div className="topbar-inner">
          <a className="brand" href="#/">
            <span className="brand-name">Government ID Generator</span>
            <span className="brand-sub">Templated identifier service</span>
          </a>
          <nav className="nav" aria-label="Main">
            <NavLink href="/" route={route}>ID Generator</NavLink>
            {user && <NavLink href="/admin" route={route}>Dashboard</NavLink>}
            {user && <NavLink href="/admin/tenants" route={route}>Tenants</NavLink>}
            {user && <NavLink href="/admin/templates" route={route}>Templates</NavLink>}
            {user && <NavLink href="/admin/credentials" route={route}>API Credentials</NavLink>}
          </nav>
          <div className="userbox">
            {user ? (
              <>
                <span className="hide-sm">Signed in as <b>{user}</b></span>
                <Button variant="ghost" size="sm" onClick={onLogout} busy={loggingOut} busyText="Signing out…">Log out</Button>
              </>
            ) : (
              route !== '/login' && <a className="btn btn-ghost btn-sm" href="#/login">Admin login</a>
            )}
          </div>
        </div>
      </header>
      <main id="main" className="container" tabIndex={-1}>{page}</main>
    </>
  );
}

import { useEffect, useRef, useState } from 'react'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { useAuth, type Action } from '../auth/AuthContext'
import { btn } from './ui'

const NAV: { to: string; label: string; end: boolean; needs?: Action }[] = [
  { to: '/', label: 'Overview', end: true },
  { to: '/sources', label: 'Sources', end: false },
  { to: '/runs', label: 'Runs', end: false },
  { to: '/quarantine', label: 'Quarantine', end: false, needs: 'job.viewRejections' as const },
  { to: '/schedules', label: 'Schedules', end: false },
]

/** Each route gets its own document title, so screen readers announce where the user has landed. */
export function pageTitle(pathname: string): string {
  const parts = pathname.split('/').filter(Boolean)
  const base = parts[0]
  const name = !base ? 'Overview'
    : base === 'sources' ? (parts[1] ? 'Source' : 'Sources')
    : base === 'runs' ? (parts[1] ? 'Run' : 'Runs')
    : base === 'quarantine' ? 'Quarantine'
    : base === 'schedules' ? 'Schedules'
    : 'PIDEA'
  return `${name} · PIDEA Console`
}

export function Layout() {
  const { username, role, signOut, can } = useAuth()
  const [open, setOpen] = useState(false)
  const { pathname } = useLocation()
  const mainRef = useRef<HTMLElement>(null)
  const firstRender = useRef(true)

  useEffect(() => {
    document.title = pageTitle(pathname)
    // After an in-app navigation, move focus to the page content so keyboard and screen-reader users start there
    // instead of at the top of the menu. Skipped on the initial load so the browser's own focus handling applies.
    if (firstRender.current) { firstRender.current = false; return }
    mainRef.current?.focus({ preventScroll: false })
  }, [pathname])


  const link = ({ isActive }: { isActive: boolean }) =>
    `block rounded-md px-3 py-2 text-sm font-medium ${isActive ? 'bg-white/15 text-white' : 'text-slate-300 hover:bg-white/10'}`

  return (
    <div className="min-h-screen md:flex">
      <a
        href="#main" className="sr-only focus:not-sr-only focus:absolute focus:left-2 focus:top-2 focus:z-50 focus:rounded focus:bg-white focus:px-3 focus:py-2 focus:text-sm focus:font-medium focus:text-brand-900 focus:shadow"
        onClick={(e) => { e.preventDefault(); mainRef.current?.focus() }}
      >
        Skip to main content
      </a>
      <header className="flex items-center justify-between bg-brand-900 px-4 py-3 text-white md:hidden">
        <span className="text-lg font-bold tracking-wide">PIDEA</span>
        <button
          type="button" className="rounded border border-white/30 px-3 py-1 text-sm"
          aria-expanded={open} aria-controls="sidebar" onClick={() => setOpen((o) => !o)}
        >
          {open ? 'Close menu' : 'Menu'}
        </button>
      </header>

      <aside id="sidebar" className={`${open ? 'block' : 'hidden'} bg-brand-900 p-4 text-white md:block md:min-h-screen md:w-60 md:shrink-0`}>
        <div className="mb-6 hidden md:block">
          <p className="text-xl font-bold tracking-wide">PIDEA</p>
          <p className="text-xs text-slate-400">Integration &amp; data platform</p>
        </div>
        <nav aria-label="Main">
          <ul className="space-y-1">
            {NAV.filter((n) => !n.needs || can(n.needs)).map((n) => (
              <li key={n.to}>
                <NavLink to={n.to} end={n.end} className={link} onClick={() => setOpen(false)}>{n.label}</NavLink>
              </li>
            ))}
          </ul>
        </nav>
        <div className="mt-8 border-t border-white/20 pt-4 text-sm">
          <p className="text-slate-300">Signed in as <span className="font-semibold text-white">{username}</span></p>
          <p className="text-xs text-slate-400" title="Inferred by probing the API; the server enforces access.">Role: {role}</p>
          <button type="button" className={`${btn.secondary} mt-3 w-full`} onClick={() => signOut()}>Sign out</button>
        </div>
      </aside>

      <main id="main" ref={mainRef} tabIndex={-1} className="min-w-0 flex-1 p-4 outline-none md:p-8">
        <div className="mx-auto max-w-6xl"><Outlet /></div>
      </main>
    </div>
  )
}

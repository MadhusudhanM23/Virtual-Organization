import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import LoginPage from './LoginPage';
import { RequireAuth } from './RequireAuth';
import { useSessionStore } from '../../stores/sessionStore';

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route
          path="/overview"
          element={
            <RequireAuth>
              <p>protected content</p>
            </RequireAuth>
          }
        />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  useSessionStore.setState({ status: 'anonymous', accessToken: null, me: null });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('LoginPage', () => {
  it('redirects anonymous visitors from protected routes to /login', () => {
    renderAt('/overview');
    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.queryByText('protected content')).not.toBeInTheDocument();
  });

  it('shows one generic, focused error for bad credentials', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ title: 'Unauthorized', status: 401 }), {
          status: 401,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
      ),
    );
    const user = userEvent.setup();
    renderAt('/login');
    await user.type(screen.getByLabelText('Organization (tenant)'), 'marutiflex-demo');
    await user.type(screen.getByLabelText('Email'), 'exec@demo.invalid');
    await user.type(screen.getByLabelText('Password'), 'wrong');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/Sign-in failed\. Check your organization, email and password/);
    expect(alert).toHaveFocus();
    // No hint about which field was wrong.
    expect(alert.textContent ?? '').not.toMatch(/unknown|no such|not found|incorrect password/i);
  });
});

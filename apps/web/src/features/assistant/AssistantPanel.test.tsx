import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { useSessionStore } from '../../stores/sessionStore';
import { useUiStore } from '../../stores/uiStore';
import { AssistantPanel } from './AssistantPanel';

/**
 * Regression (plan 10.1, found by the real-stack MCD suite): the panel selected `roles.map(...)` from the session
 * store, a new array on every snapshot, which React's useSyncExternalStore never settles on. With a signed-in user it
 * threw "Maximum update depth exceeded" and the route error boundary replaced the page.
 */
describe('AssistantPanel', () => {
  it('renders for a signed-in user with roles without an update loop', () => {
    useUiStore.setState({ selectedSiteId: 'site-1' });
    useSessionStore.setState({
      status: 'authenticated',
      accessToken: 'tok',
      me: {
        actor_ref: 'a',
        display_name: 'T',
        tenant: { id: 't', slug: 's', name: 'N', demo_mode: true },
        roles: [{ role: 'WAREHOUSE_MANAGER' as never, scope_type: 'SITE', scope_id: 'site-1' }],
      },
    });
    render(
      <MemoryRouter>
        <AssistantPanel variant="page" />
      </MemoryRouter>,
    );
    expect(screen.getByTestId('assistant-panel')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Why does the bottleneck matter?' })).toBeTruthy();
  });
});

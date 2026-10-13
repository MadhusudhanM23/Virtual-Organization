import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { useUiStore } from '../../stores/uiStore';
import { ReplayInspectorBody } from './ReplayInspector';
import { useReplayStore } from './replayStore';
import { T0, entity, state } from './testFixtures';

describe('Replay inspector', () => {
  beforeEach(() => {
    useReplayStore.getState().reset();
    useUiStore.setState({
      lens: 'REPLAY',
      sites: [
        {
          id: 'site-1',
          name: 'Site',
          kind: 'WAREHOUSE',
          time_zone: 'Asia/Kolkata',
          locale: 'en-IN',
          currency: 'INR',
          unit_system: 'METRIC',
        },
      ],
      selectedSiteId: 'site-1',
    });
  });

  it('shows the entity as of replay time with "reconstructed from snapshot X + N events" provenance', () => {
    const s = state(T0, [entity('a', 12.5, 7.25)]);
    useReplayStore.setState({
      at: T0,
      base: {
        at: T0,
        reconstructedFrom: s.reconstructed_from,
        notReconstructed: s.not_reconstructed,
        recorded: s.recorded,
        stateHash: s.state_hash,
      },
    });
    render(<ReplayInspectorBody entity={s.entities[0]!} onClear={() => undefined} />);
    expect(screen.getByText(/As of replay time 12 Oct 2026, 02:30:00 pm IST/i)).toBeInTheDocument();
    expect(screen.getByTestId('replay-inspector-position')).toHaveTextContent('12.50, 7.25, 0.00');
    const provenance = screen.getByTestId('replay-provenance');
    expect(provenance).toHaveTextContent('Reconstructed from snapshot 0199d1c2');
    expect(provenance).toHaveTextContent('+ 11 events');
    expect(provenance).toHaveTextContent('12 events read, 1 ignored, 0 skipped');
    expect(provenance).not.toHaveTextContent('advanced by playback');
    // Original data mode (SIMULATED), never a LIVE label.
    expect(screen.getAllByRole('img', { name: /simulated/i }).length).toBeGreaterThan(0);
    expect(screen.queryByRole('img', { name: /live/i })).toBeNull();
  });

  it('states when playback frames advanced the scene past the base read', () => {
    const s = state(T0, [entity('a', 1, 1)]);
    useReplayStore.setState({
      at: T0 + 7000,
      advancedByFrames: true,
      base: {
        at: T0,
        reconstructedFrom: s.reconstructed_from,
        notReconstructed: [],
        recorded: s.recorded,
        stateHash: s.state_hash,
      },
    });
    render(<ReplayInspectorBody entity={s.entities[0]!} onClear={() => undefined} />);
    expect(screen.getByTestId('replay-provenance')).toHaveTextContent(
      /Then advanced by playback frames to 12 Oct 2026, 02:30:07 pm IST/i,
    );
  });
});

import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Entity } from '@vo/twin-schema';
import { EventsPanelView } from './EventsPanel';
import { mergeEvents, summarizeEvent, type EventGroup } from './eventsModel';
import type { EventEnvelope } from './liveProtocol';

function ev(id: string, type: string, at: string, payload: Record<string, unknown> = {}): EventEnvelope {
  return {
    event_id: id,
    event_type: type,
    aggregate_type: type.startsWith('TASK') ? 'TASK' : 'FORKLIFT',
    aggregate_id: `agg-${id}`,
    occurred_at: at,
    source: 'SIMULATION',
    data_mode: 'SIMULATED',
    payload,
  };
}

const forklift = { id: 'e-fl08', code: 'FL-08', pose: { x: 1, y: 2 } } as unknown as Entity;

const EVENTS = [
  ev('3', 'TASK_COMPLETED', '2026-01-01T00:00:03Z', {
    task_type: 'PICK',
    from_status: 'STARTED',
    to_status: 'COMPLETED',
    assigned_entity_code: 'FL-08',
  }),
  ev('2', 'INTEGRATION_FINDING_RECORDED', '2026-01-01T00:00:02Z', {
    finding_type: 'DATA_QUALITY',
    severity: 'MEDIUM',
  }),
  ev('1', 'FORKLIFT_POSITION_UPDATED', '2026-01-01T00:00:01Z', { entity_code: 'FL-08' }),
];

const DEFAULT_GROUPS: ReadonlySet<EventGroup> = new Set(['tasks', 'integration', 'site']);

describe('EventsPanelView', () => {
  it('lists events of the selected groups with their data mode, newest first', () => {
    render(
      <EventsPanelView
        events={EVENTS}
        groups={DEFAULT_GROUPS}
        onToggleGroup={() => undefined}
        entities={[forklift]}
        onFocusEntity={() => undefined}
      />,
    );
    const rows = screen.getAllByTestId('event-row');
    expect(rows.map((r) => r.dataset.eventType)).toEqual([
      'TASK_COMPLETED',
      'INTEGRATION_FINDING_RECORDED',
    ]);
    expect(within(rows[0]!).getByRole('img', { name: /simulated/i })).toBeInTheDocument();
    expect(screen.getByRole('list', { name: /operational events/i })).toHaveAttribute(
      'aria-live',
      'polite',
    );
  });

  it('shows movement events only when the group is on', () => {
    render(
      <EventsPanelView
        events={EVENTS}
        groups={new Set<EventGroup>([...DEFAULT_GROUPS, 'movement'])}
        onToggleGroup={() => undefined}
        entities={[]}
        onFocusEntity={() => undefined}
      />,
    );
    expect(screen.getAllByTestId('event-row')).toHaveLength(3);
  });

  it('reports filter changes', async () => {
    const onToggle = vi.fn();
    render(
      <EventsPanelView
        events={EVENTS}
        groups={DEFAULT_GROUPS}
        onToggleGroup={onToggle}
        entities={[]}
        onFocusEntity={() => undefined}
      />,
    );
    await userEvent.click(screen.getByRole('checkbox', { name: 'Movement' }));
    expect(onToggle).toHaveBeenCalledWith('movement');
  });

  it('focuses the entity of a row on click', async () => {
    const onFocus = vi.fn();
    render(
      <EventsPanelView
        events={EVENTS}
        groups={DEFAULT_GROUPS}
        onToggleGroup={() => undefined}
        entities={[forklift]}
        onFocusEntity={onFocus}
      />,
    );
    await userEvent.click(screen.getByRole('button', { name: /show FL-08 in the twin/i }));
    expect(onFocus).toHaveBeenCalledWith(forklift);
  });

  it('pauses: the list freezes, stops announcing and counts waiting events', async () => {
    const { rerender } = render(
      <EventsPanelView
        events={EVENTS}
        groups={DEFAULT_GROUPS}
        onToggleGroup={() => undefined}
        entities={[]}
        onFocusEntity={() => undefined}
      />,
    );
    await userEvent.click(screen.getByRole('button', { name: /pause feed/i }));
    const newer = ev('4', 'TASK_STARTED', '2026-01-01T00:00:04Z');
    rerender(
      <EventsPanelView
        events={[newer, ...EVENTS]}
        groups={DEFAULT_GROUPS}
        onToggleGroup={() => undefined}
        entities={[]}
        onFocusEntity={() => undefined}
      />,
    );
    expect(screen.getAllByTestId('event-row')).toHaveLength(2);
    expect(screen.getByRole('list', { name: /operational events/i })).toHaveAttribute(
      'aria-live',
      'off',
    );
    expect(screen.getByTestId('events-feed-state')).toHaveTextContent('1 new events waiting');
    await userEvent.click(screen.getByRole('button', { name: /resume feed/i }));
    expect(screen.getAllByTestId('event-row')).toHaveLength(3);
  });

  it('says so when nothing matches', () => {
    render(
      <EventsPanelView
        events={[]}
        groups={DEFAULT_GROUPS}
        onToggleGroup={() => undefined}
        entities={[]}
        onFocusEntity={() => undefined}
      />,
    );
    expect(screen.getByText(/no events match/i)).toBeInTheDocument();
  });
});

describe('eventsModel', () => {
  it('summarizes tasks from the payload only', () => {
    const s = summarizeEvent(EVENTS[0]!);
    expect(s.title).toBe('Task completed');
    expect(s.detail).toBe('PICK · STARTED to COMPLETED');
    expect(s.entityCode).toBe('FL-08');
  });

  it('merges newest first, dedupes by id and caps movement events', () => {
    const a = mergeEvents([], EVENTS);
    expect(a.map((e) => e.event_id)).toEqual(['3', '2', '1']);
    expect(mergeEvents(a, EVENTS)).toHaveLength(3);
    const flood = Array.from({ length: 150 }, (_, i) =>
      ev(`m${i}`, 'FORKLIFT_POSITION_UPDATED', `2026-01-01T00:01:${String(i % 60).padStart(2, '0')}Z`),
    );
    const merged = mergeEvents(EVENTS, flood);
    expect(merged.filter((e) => e.event_type === 'FORKLIFT_POSITION_UPDATED')).toHaveLength(100);
    expect(merged.some((e) => e.event_id === '3')).toBe(true);
  });
});

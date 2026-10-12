import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { usePolled } from './usePolled';

function setHidden(hidden: boolean) {
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });
  document.dispatchEvent(new Event('visibilitychange'));
}

describe('usePolled', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    setHidden(false);
    vi.useRealTimers();
  });

  it('loads, polls on the interval and pauses while the tab is hidden', async () => {
    const load = vi.fn().mockResolvedValue('v');
    const { result } = renderHook(() => usePolled(load, 'k', 1000));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(result.current.data).toBe('v');
    expect(load).toHaveBeenCalledTimes(1);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });
    expect(load).toHaveBeenCalledTimes(2);

    setHidden(true);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3000);
    });
    expect(load).toHaveBeenCalledTimes(2);

    setHidden(false); // refreshes right away when visible again
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(load).toHaveBeenCalledTimes(3);
  });

  it('keeps the last data when a refresh fails and reports the error', async () => {
    const load = vi.fn().mockResolvedValueOnce('ok').mockRejectedValue(new Error('boom'));
    const { result } = renderHook(() => usePolled(load, 'k', 1000));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });
    expect(result.current.data).toBe('ok');
    expect(result.current.error).toBeInstanceOf(Error);
  });

  it('does not load while disabled', async () => {
    const load = vi.fn().mockResolvedValue('v');
    renderHook(() => usePolled(load, 'k', 1000, false));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000);
    });
    expect(load).not.toHaveBeenCalled();
  });
});

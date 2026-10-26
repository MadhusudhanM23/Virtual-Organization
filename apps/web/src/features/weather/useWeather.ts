import { useEffect, useState } from 'react';
import type { MessageId } from '../../i18n';

/** Current conditions from Open-Meteo (free, no key, CC BY 4.0). Called straight from the browser, no credentials. */
export interface Weather {
  temperatureC: number;
  code: number;
  observedAt: string;
}

export type WeatherKind =
  'clear' | 'partly' | 'cloudy' | 'fog' | 'drizzle' | 'rain' | 'snow' | 'storm';

const REFRESH_MS = 15 * 60_000;
const ENDPOINT = 'https://api.open-meteo.com/v1/forecast';

/** WMO weather interpretation codes (as documented by Open-Meteo) to a coarse kind. */
export function weatherKind(code: number): WeatherKind {
  if (code === 0) return 'clear';
  if (code <= 2) return 'partly';
  if (code === 3) return 'cloudy';
  if (code === 45 || code === 48) return 'fog';
  if (code >= 51 && code <= 57) return 'drizzle';
  if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return 'rain';
  if ((code >= 71 && code <= 77) || code === 85 || code === 86) return 'snow';
  if (code >= 95) return 'storm';
  return 'cloudy';
}

export const WEATHER_LABELS: Record<WeatherKind, MessageId> = {
  clear: 'weather.clear',
  partly: 'weather.partly',
  cloudy: 'weather.cloudy',
  fog: 'weather.fog',
  drizzle: 'weather.drizzle',
  rain: 'weather.rain',
  snow: 'weather.snow',
  storm: 'weather.storm',
};

export async function fetchWeather(
  lat: number,
  lon: number,
  signal?: AbortSignal,
): Promise<Weather> {
  const q = new URLSearchParams({
    latitude: lat.toFixed(4),
    longitude: lon.toFixed(4),
    current: 'temperature_2m,weather_code',
    timezone: 'UTC',
  });
  const res = await fetch(`${ENDPOINT}?${q.toString()}`, {
    signal: signal ?? null,
    credentials: 'omit',
  });
  if (!res.ok) throw new Error(`weather ${res.status}`);
  const body = (await res.json()) as {
    current?: { time?: string; temperature_2m?: number; weather_code?: number };
  };
  const c = body.current;
  if (typeof c?.temperature_2m !== 'number' || typeof c.weather_code !== 'number') {
    throw new Error('weather: no current conditions');
  }
  return { temperatureC: c.temperature_2m, code: c.weather_code, observedAt: c.time ?? '' };
}

/** Current weather for a location, refreshed every 15 minutes; null while loading, on error or without a location. */
export function useWeather(
  lat: number | null | undefined,
  lon: number | null | undefined,
): Weather | null {
  const key = typeof lat === 'number' && typeof lon === 'number' ? `${lat},${lon}` : null;
  // Results are keyed by location, so a stale reading from another site is never shown.
  const [slot, setSlot] = useState<{ key: string; weather: Weather | null } | null>(null);
  useEffect(() => {
    if (key === null || typeof lat !== 'number' || typeof lon !== 'number') return undefined;
    let ctrl = new AbortController();
    const load = () => {
      ctrl.abort();
      ctrl = new AbortController();
      const signal = ctrl.signal;
      fetchWeather(lat, lon, signal).then(
        (weather) => setSlot({ key, weather }),
        () => {
          if (!signal.aborted) setSlot({ key, weather: null });
        },
      );
    };
    load();
    const timer = window.setInterval(load, REFRESH_MS);
    return () => {
      window.clearInterval(timer);
      ctrl.abort();
    };
  }, [key, lat, lon]);
  return slot && slot.key === key ? slot.weather : null;
}

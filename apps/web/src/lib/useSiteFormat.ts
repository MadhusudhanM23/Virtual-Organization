import { useMemo } from 'react';
import { useUiStore } from '../stores/uiStore';
import {
  formatCurrency,
  formatDateTime,
  formatDateTimeSeconds,
  formatDistance,
  formatTimeSeconds,
  formatNumber,
  type UnitSystem,
} from './format';

/**
 * Formatters bound to the SELECTED site's time zone, locale, currency and unit system (BLUEPRINT section 15).
 * Nothing here assumes a country: with no site selected (or a caller with technical metadata only) it falls
 * back to the browser locale and UTC, and `currency` is null so callers cannot print a made-up currency.
 */
export interface SiteFormat {
  timeZone: string;
  locale: string;
  currency: string | null;
  unitSystem: UnitSystem;
  dateTime: (isoUtc: string) => string;
  /** With seconds (replay time). */
  dateTimeSeconds: (isoUtc: string) => string;
  /** Time of day with seconds. */
  timeSeconds: (isoUtc: string) => string;
  number: (value: number, options?: Intl.NumberFormatOptions) => string;
  /** Null when the site has no currency configured. */
  money: (amount: number) => string | null;
  distance: (meters: number) => string;
}

export function useSiteFormat(): SiteFormat {
  const site = useUiStore((s) => s.sites.find((x) => x.id === s.selectedSiteId) ?? null);

  return useMemo(() => {
    const timeZone = site?.time_zone ?? 'UTC';
    const locale =
      site?.locale ?? (typeof navigator === 'undefined' ? 'en' : navigator.language || 'en');
    const currency = site?.currency ?? null;
    const unitSystem: UnitSystem = site?.unit_system === 'IMPERIAL' ? 'imperial' : 'metric';
    return {
      timeZone,
      locale,
      currency,
      unitSystem,
      dateTime: (isoUtc) => formatDateTime(isoUtc, { timeZone, locale }),
      dateTimeSeconds: (isoUtc) => formatDateTimeSeconds(isoUtc, { timeZone, locale }),
      timeSeconds: (isoUtc) => formatTimeSeconds(isoUtc, { timeZone, locale }),
      number: (value, options) => formatNumber(value, locale, options),
      money: (amount) => (currency ? formatCurrency(amount, currency, locale) : null),
      distance: (meters) => formatDistance(meters, unitSystem, locale),
    };
  }, [site]);
}

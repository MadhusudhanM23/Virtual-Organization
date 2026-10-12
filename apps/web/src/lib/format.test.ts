import { describe, expect, it } from 'vitest';
import { formatCurrency, formatDateTime, formatDistance, formatNumber } from './format';

describe('formatDateTime', () => {
  const instant = '2026-10-09T10:24:18Z';

  it('renders the same UTC instant differently for Asia/Kolkata and Europe/Berlin', () => {
    const kolkata = formatDateTime(instant, { timeZone: 'Asia/Kolkata', locale: 'en-GB' });
    const berlin = formatDateTime(instant, { timeZone: 'Europe/Berlin', locale: 'en-GB' });

    expect(kolkata).not.toEqual(berlin);
    // 10:24 UTC = 15:54 IST (UTC+5:30) = 12:24 CEST (UTC+2 on 9 Oct 2026).
    expect(kolkata).toContain('15:54');
    expect(berlin).toContain('12:24');
  });

  it('shows the time zone so the reader is never left guessing', () => {
    const kolkata = formatDateTime(instant, { timeZone: 'Asia/Kolkata', locale: 'en-GB' });
    expect(kolkata).toMatch(/GMT\+5:30|IST/);
  });

  it('applies the locale to the date part', () => {
    const us = formatDateTime(instant, { timeZone: 'UTC', locale: 'en-US' });
    const de = formatDateTime(instant, { timeZone: 'UTC', locale: 'de-DE' });
    expect(us).toContain('Oct');
    expect(de).toContain('Okt');
  });

  it('throws on an invalid timestamp rather than rendering "Invalid Date"', () => {
    expect(() => formatDateTime('not-a-date', { timeZone: 'UTC', locale: 'en-GB' })).toThrow(
      RangeError,
    );
  });
});

describe('formatCurrency', () => {
  it('honors INR versus EUR, including locale grouping', () => {
    const inr = formatCurrency(123456.5, 'INR', 'en-IN');
    const eur = formatCurrency(1234.5, 'EUR', 'de-DE');

    expect(inr).toContain('₹');
    expect(inr).toContain('1,23,456.50'); // Indian lakh grouping
    expect(eur).toContain('€');
    expect(eur).toMatch(/1\.234,50\s€/); // dot thousands, comma decimals, symbol after
  });

  it('formats the same amount differently per currency', () => {
    expect(formatCurrency(10, 'INR', 'en-IN')).not.toEqual(formatCurrency(10, 'EUR', 'en-IN'));
  });
});

describe('formatNumber', () => {
  it('uses the locale separators', () => {
    expect(formatNumber(1234567.891, 'en-US', { maximumFractionDigits: 2 })).toBe('1,234,567.89');
    expect(formatNumber(1234567.891, 'de-DE', { maximumFractionDigits: 2 })).toBe('1.234.567,89');
  });
});

describe('formatDistance', () => {
  it('formats metric distances as meters or kilometers', () => {
    expect(formatDistance(250, 'metric', 'en-US')).toBe('250 m');
    expect(formatDistance(2500, 'metric', 'en-US')).toBe('2.5 km');
  });

  it('formats imperial distances as feet or miles from the same meter value', () => {
    expect(formatDistance(100, 'imperial', 'en-US')).toBe('328 ft');
    expect(formatDistance(5000, 'imperial', 'en-US')).toBe('3.1 mi');
  });
});

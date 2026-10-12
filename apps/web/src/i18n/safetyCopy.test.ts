import { describe, expect, it } from 'vitest';
import { messagesEn } from './messages.en';

/**
 * REQ-056 automated copy check: no screen may claim a safety rating or certification, and everything about safety is
 * labeled advisory. All UI copy lives in the message catalog, so scanning it covers every screen.
 */

const FORBIDDEN: { rule: string; re: RegExp }[] = [
  { rule: 'certification claim', re: /\bcertif(?:ied|ication|ications|y|ies)\b/i },
  { rule: 'SIL level', re: /\bSIL[\s-]?[0-4]\b/ },
  { rule: 'safety integrity level', re: /safety integrity level/i },
  { rule: 'guarantee', re: /\bguarantee/i },
  { rule: 'prevents accidents', re: /\bprevent(?:s|ing)?\s+(?:all\s+)?(?:accidents|collisions|injur)/i },
  { rule: 'ensures safety', re: /\bensur(?:e|es|ing)\s+(?:worker\s+|site\s+)?safety\b/i },
  { rule: 'fail-safe', re: /\bfail[\s-]?safe\b/i },
  { rule: 'accident-free', re: /\baccident[\s-]free\b/i },
  { rule: 'standard compliance claim', re: /\b(?:ISO\s?13849|IEC\s?61508|IEC\s?62061|ISO\s?3691)\b/i },
  { rule: 'compliant claim', re: /\bsafety[\s-]compliant\b/i },
];

/** "safety-rated" may only appear negated ("not a safety-rated ..."). */
function ratedClaim(text: string): boolean {
  const re = /safety[\s-]rated/gi;
  let m: RegExpExecArray | null;
  while ((m = re.exec(text)) !== null) {
    const before = text.slice(Math.max(0, m.index - 12), m.index).toLowerCase();
    if (!/\bnot (?:a |an )?$/.test(before)) return true;
  }
  return false;
}

/** Any mention of an emergency stop (other than the physical one on the equipment) must say it is advisory. */
function unlabeledEstop(text: string): boolean {
  const mentions = /emergency[\s-]stop/i.test(text.replace(/hardware emergency[\s-]stop/gi, ''));
  return mentions && !/advisory/i.test(text);
}

export function copyViolations(catalog: Record<string, string>): string[] {
  const out: string[] = [];
  for (const [id, text] of Object.entries(catalog)) {
    for (const f of FORBIDDEN) if (f.re.test(text)) out.push(`${id}: ${f.rule}`);
    if (ratedClaim(text)) out.push(`${id}: safety-rated claim`);
    if (unlabeledEstop(text)) out.push(`${id}: emergency stop not labeled advisory`);
  }
  return out;
}

describe('safety copy check (REQ-056)', () => {
  it('the message catalog makes no safety-rating or certification claim', () => {
    expect(copyViolations(messagesEn)).toEqual([]);
  });

  it('the advisory statement exists and says what the platform is not', () => {
    const notice = messagesEn['safety.advisory.notice'];
    expect(notice).toMatch(/not a safety-rated function/);
    expect(notice).toMatch(/never stops a machine/);
    expect(messagesEn['estop.explain']).toMatch(/does NOT stop any machine/);
  });

  it('the checker catches claims (self-test)', () => {
    expect(
      copyViolations({
        a: 'Certified safety system',
        b: 'SIL 2 rated',
        c: 'This guarantees safe operation',
        d: 'It prevents accidents on the floor',
        e: 'A safety-rated function',
        f: 'Emergency stop',
        g: 'Compliant with ISO 13849',
      }).map((v) => v.split(':')[0]),
    ).toEqual(['a', 'b', 'c', 'd', 'e', 'f', 'g']);
    expect(
      copyViolations({
        ok1: 'This platform is not a safety-rated function.',
        ok2: 'Advisory emergency-stop request',
        ok3: 'In an emergency, use the hardware emergency stop on the equipment.',
      }),
    ).toEqual([]);
  });
});

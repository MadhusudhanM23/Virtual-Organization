// Secrets scan of every git-tracked file (Plan 11.3, NFR-14). Node 22+, no packages.
//   node scripts/security/secrets-scan.mjs [--self-test]
// Exit 1 on a finding. Dev-only placeholders are allowed in an explicit, reviewed list; anything else fails.
import { execFileSync } from 'node:child_process';
import { readFileSync, statSync } from 'node:fs';

const RULES = [
  { id: 'private-key', re: /-----BEGIN (?:RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----(?!["'`])/ },
  { id: 'aws-access-key', re: /\bAKIA[0-9A-Z]{16}\b/ },
  { id: 'openai-key', re: /\bsk-(?:proj-)?[A-Za-z0-9_-]{24,}\b/ },
  { id: 'github-token', re: /\bgh[pousr]_[A-Za-z0-9]{30,}\b/ },
  { id: 'slack-token', re: /\bxox[baprs]-[A-Za-z0-9-]{10,}\b/ },
  { id: 'google-api-key', re: /\bAIza[0-9A-Za-z_-]{35}\b/ },
  { id: 'jwt', re: /\beyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b/ },
  {
    id: 'hard-coded-password',
    re: /\b(?:password|passwd|secret|api[_-]?key)\s*[:=]\s*["']?(?![<$%{]|\s|process|os\.|__ENV|not-a-real|your|change|example|placeholder|test|dummy|xxx|\*\*|null|none|true|false)[A-Za-z0-9/+_.!@#%^&*-]{12,}["']?/i,
  },
];

// Files that may contain fixtures or documented dev-only placeholders. Reviewed one by one.
const ALLOW_FILES = [
  /^\.env\.example$/, // dev-only placeholders by design
  /^docs\//, // documentation shows example commands and placeholder values
  /^scripts\/security\/secrets-scan\.mjs$/, // this file holds the patterns
  /\/src\/test\//, // Java tests: synthetic fixtures (e.g. the SecretPatterns tests)
  /\/tests?\//, // Python/JS tests
  /\.test\.(?:ts|tsx|js|mjs)$/,
  /^apps\/web\/e2e/, // Playwright suites use the demo password from the environment
  /^package-lock\.json$/,
];
const FORBIDDEN_TRACKED = [/(^|\/)\.env$/, /(^|\/)\.env\.(?!example$).+/, /\.pem$/, /\.p12$/, /\.pfx$/, /id_rsa$/, /\.jks$/];

function scanText(name, text) {
  const hits = [];
  text.split(/\r?\n/).forEach((line, i) => {
    for (const r of RULES) if (r.re.test(line)) hits.push({ file: name, line: i + 1, rule: r.id });
  });
  return hits;
}

if (process.argv.includes('--self-test')) {
  const cases = [
    ['private-key', '-----BEGIN RSA PRIVATE KEY-----'],
    ['aws-access-key', 'AKIAABCDEFGHIJKLMNOP'],
    ['openai-key', 'key = sk-proj-abcdefghijklmnopqrstuvwx'],
    ['hard-coded-password', 'password = "Sup3rS3cretValue99"'],
  ];
  for (const [rule, text] of cases) {
    if (!scanText('x', text).some((h) => h.rule === rule)) throw new Error(`self-test: ${rule} not detected`);
  }
  for (const ok of ['password: ${DEMO_USER_PASSWORD}', 'password=change-me-owner', 'password: <secret>']) {
    if (scanText('x', ok).length) throw new Error(`self-test: false positive on "${ok}"`);
  }
  console.log('self-test ok');
  process.exit(0);
}

const files = execFileSync('git', ['ls-files', '-z'], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 })
  .split('\0')
  .filter(Boolean);
const findings = [];
for (const f of files) {
  if (FORBIDDEN_TRACKED.some((re) => re.test(f))) findings.push({ file: f, line: 0, rule: 'forbidden-tracked-file' });
  if (ALLOW_FILES.some((re) => re.test(f))) continue;
  let st;
  try {
    st = statSync(f);
  } catch {
    continue; // deleted in the working tree
  }
  if (!st.isFile() || st.size > 2 * 1024 * 1024) continue;
  const buf = readFileSync(f);
  if (buf.includes(0)) continue; // binary
  findings.push(...scanText(f, buf.toString('utf8')));
}
if (findings.length) {
  for (const x of findings) console.error(`${x.file}:${x.line}  ${x.rule}`);
  console.error(`secrets scan: ${findings.length} finding(s) in ${files.length} tracked files`);
  process.exit(1);
}
console.log(`secrets scan: clean (${files.length} tracked files, ${RULES.length} rules, ${FORBIDDEN_TRACKED.length} forbidden-file patterns)`);

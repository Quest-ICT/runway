// The page script's word pattern (static/highlight.js) on the cases Java and the browser fold differently.
// Run: node src/test/js/highlight-pattern.js static/highlight.js   (verify-against-host.sh runs it when node is present)
const fs = require('fs');
const src = fs.readFileSync(process.argv[2], 'utf8');
const esc = src.match(/function escapeRegExp[\s\S]*?\n    }\n/)[0];
const pat = src.match(/function pattern[\s\S]*?\n    }\n/)[0];
const pattern = new Function(esc + pat + 'return pattern;')();
const javaLower = 'İstanbul'.toLowerCase(); // JS gives i̇stanbul too
const cases = [
  [[javaLower], 'Visit İstanbul and istanbul.', 2],
  [['istanbul'], 'İstanbul', 1],
  [['maestría'], 'MAESTRÍA y maestrías', 1],
  [["c++"], "learn c++ now", 1],  // a word ending in symbols still matches whole
  [['a.b'], 'a.b axb', 1],
];
let ok = true;
for (const [w, text, want] of cases) {
  const got = (text.match(pattern(w)) || []).length;
  console.log(JSON.stringify(w), JSON.stringify(text), got, got === want ? 'ok' : 'WANT ' + want);
  if (got !== want) ok = false;
}
process.exit(ok ? 0 : 1);

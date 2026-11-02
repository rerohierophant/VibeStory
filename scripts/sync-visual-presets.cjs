// Generate the backend catalog from the implemented frontend, including its existing image attribution paths.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const root = path.resolve(__dirname, '..');
const requireWeb = createRequire(path.join(root, 'apps/web/package.json'));
const ts = requireWeb('typescript');
const source = fs.readFileSync(path.join(root, 'apps/web/src/lib/visualPresets.ts'), 'utf8');
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText;
const context = { exports: {} };
vm.runInNewContext(compiled, context);
const presets = context.exports.visualPresets.map(item => ({ ...item, custom: false }));
if (!presets.length || new Set(presets.map(item => item.id)).size !== presets.length) throw new Error('Invalid preset catalog');
const output = path.join(root, 'apps/api/src/main/resources/visual-presets.json');
const content = JSON.stringify(presets, null, 2) + '\n';
if (process.argv.includes('--check')) {
  if (fs.readFileSync(output, 'utf8') !== content) throw new Error('Backend catalog differs; run node scripts/sync-visual-presets.cjs');
  console.log(`Verified ${presets.length} presets match the implemented frontend`);
} else {
  fs.writeFileSync(output, content);
  console.log(`Synced ${presets.length} presets from apps/web/src/lib/visualPresets.ts`);
}

// Evaluate the actual frontend splitter to produce an offline parity corpus for the Java Mock.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {createRequire} = require('node:module');
const root = path.resolve(__dirname, '..');
const ts = createRequire(path.join(root, 'apps/web/package.json'))('typescript');
const source = fs.readFileSync(path.join(root, 'apps/web/src/lib/splitter.ts'), 'utf8');
const context = {exports: {}};
vm.runInNewContext(ts.transpileModule(source, {compilerOptions: {module: ts.ModuleKind.CommonJS}}).outputText, context);
const cases = [
  {name:'paragraphs', story:'雨夜，街道上积水发亮。她推门走进便利店。\n\n货架后，店员放下牛奶。\n继母坐在客厅沙发上，茶几上有一张单据。'},
  {name:'quoted-attribution', story:'「要一份萝卜。」她说。店员抬起头，停顿片刻。\n她说：「不要放辣椒。」'},
  {name:'bare-quote', story:'「你终于回来了」\n「别走」。她开口。\n他问：「你等了多久？」'},
  {name:'headings', story:'一、第一幕\n路灯下落着雨。\n二、第二幕\n便利店的自动门缓缓打开。'},
  {name:'long-aggregation', story:'雨打在她的伞上，路灯把积水照成一片晃动的银色。她回头望着街道尽头久久没有说话。门口的风铃突然响了。店员递过纸碗，她低下头。'},
  {name:'single-long-sentence', story:'雨'.repeat(145)+'。'},
  {name:'custom-scenes', story:'古桥的风吹过石阶。门口灯光发亮。院子里桂花落下。', scenes:[{name:'夜晚的古桥'},{name:'旧宅，院子'}]},
  {name:'custom-keywords', story:'桂花和路灯照着石阶。', scenes:[{name:'自选甲',keywords:[]},{name:'自选乙',keywords:['桂花','石阶']}]},
  {name:'empty-quotes', story:'「」雨夜的路灯亮了。店员递来纸碗。'},
  {name:'unicode-crlf', story:'\ufeff　雨夜街道🙂。\r\n\r\n　「回来啦！」她喊。\r\n便利店里有热牛奶。\u00a0'},
  {name:'punctuation', story:'她问：「真的吗？」他答：「当然！」。门外又响起脚步声。'},
  {name:'empty', story:'\n　\n一、标题\n'},
];
const result = cases.map(item => ({...item, expected:context.exports.splitIntoShots(item.story,item.scenes)}));
const output = path.join(root,'apps/api/src/test/resources/splitter-cases.json');
const content = JSON.stringify(result,null,2)+'\n';
if (process.argv.includes('--check')) {
  if (fs.readFileSync(output,'utf8') !== content) throw new Error('Splitter fixtures differ from frontend; regenerate and review Java parity');
  console.log(`Verified ${result.length} splitter cases against the actual frontend`);
} else {
  fs.mkdirSync(path.dirname(output),{recursive:true}); fs.writeFileSync(output,content);
  console.log(`Generated ${result.length} parity cases from the actual frontend`);
}

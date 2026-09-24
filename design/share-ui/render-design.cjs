// Re-render the design proposal and verify its local interactions. No app/server writes.
const fs = require('node:fs');
const path = require('node:path');
const runtime = 'C:/Users/nxvan/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules';
const { chromium } = require(path.join(runtime, 'playwright'));
const source = path.join(__dirname, 'prototype.html');
const lucide = fs.readFileSync(path.join(runtime, 'lucide/dist/umd/lucide.js'), 'utf8');
const fragment = fs.readFileSync(source, 'utf8');
const scenes = [
 ['album','01-album','专辑 · 右上角分享'], ['playlist','02-playlist','歌单 · 右上角分享'],
 ['song','03-song','单曲 · 更多菜单'], ['create','04-create','设置有效期 · 默认 7 天'],
 ['success','05-success','复制或分享链接'], ['my','06-my','我的 · 底部分享栏目'],
 ['list','07-my-shares','我的分享 · 当前账号'], ['manage','08-manage','管理详情 · 复用原链接'],
 ['edit','09-edit','编辑说明与有效期'], ['revoke','10-revoke','取消分享 · 确认'],
 ['empty','11-empty','无分享 · 整栏隐藏'], ['error','12-disabled','服务器未开启分享']
];
(async()=>{
 const browser = await chromium.launch({headless:true, executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
 const page = await browser.newPage({viewport:{width:760,height:1100},deviceScaleFactor:2,colorScheme:'light'});
 const errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 await page.setContent('<!doctype html><html><head><meta charset="utf-8"><style>body{margin:0;background:#fff;font-family:Microsoft YaHei,sans-serif}</style><script>'+lucide+'</script></head><body>'+fragment+'</body></html>');
 await page.locator('#ct-phone svg').first().waitFor();
 for(const [value,file] of scenes){
   await page.selectOption('#ct-scene',value);
   await page.locator('#ct-phone').screenshot({path:path.join(__dirname,file+'.png')});
 }
 // Core navigation and state transitions must function in the proposal.
 await page.selectOption('#ct-scene','album');
 await page.getByRole('button',{name:'分享专辑',exact:true}).click();
 await page.locator('#ct-description').fill('把这张专辑分享给你');
 await page.getByRole('button',{name:'30 天',exact:true}).click();
 if(await page.locator('#ct-description').inputValue()!=='把这张专辑分享给你') throw Error('Draft lost when changing expiry');
 await page.getByRole('button',{name:'生成分享链接',exact:true}).click();
 if(!(await page.locator('#ct-phone').innerText()).includes('2026-10-24')) throw Error('Expiry not reflected');
 await page.getByRole('button',{name:'管理此分享'}).click();
 await page.getByRole('button',{name:'取消分享',exact:true}).click();
 await page.getByRole('button',{name:'保留链接',exact:true}).click();
 await page.getByRole('button',{name:'取消分享',exact:true}).click();
 await page.getByRole('button',{name:'取消分享',exact:true}).last().click();
 if(await page.locator('#ct-scene').inputValue()!=='list') throw Error('Revoke did not return to list');
 await page.selectOption('#ct-scene','my');
 if(!(await page.locator('#ct-phone').innerText()).includes('我的分享')) throw Error('Share entry missing when shares exist');
 await page.selectOption('#ct-scene','empty');
 if((await page.locator('#ct-phone').innerText()).includes('我的分享')) throw Error('Share entry shown without shares');
 // 320px layout + dark theme smoke checks on the important states.
 await page.setViewportSize({width:320,height:1100});
 for(const value of ['create','success','my','empty','list','revoke']){
   await page.selectOption('#ct-scene',value);
   const overflow=await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth);
   if(overflow) throw Error('Horizontal overflow at 320px: '+value);
 }
 await page.setViewportSize({width:760,height:1100});
 await page.emulateMedia({colorScheme:'dark'});
 await page.selectOption('#ct-scene','create');
 await page.locator('#ct-description').fill('分享一些路上听的歌');
 await page.getByRole('button',{name:'7 天',exact:true}).click();
 await page.locator('#ct-phone').screenshot({path:path.join(__dirname,'13-dark-create.png')});
 if(errors.length)throw Error(errors.join('\n'));
 // Compose exported review boards from the actual rendered mockup images.
 await page.emulateMedia({colorScheme:'light'});
 await page.setViewportSize({width:1320,height:1990});
 for(const [file,title,subtitle,indices] of [
  ['a-share-flow','把喜欢的音乐，分享出去','ClearTune / 分享设计 V1 · 入口与创建流程',[0,1,2,3,4,5]],
  ['b-share-management','每一条链接，都可以管理','ClearTune / 我的 → 分享 → 我的分享',[6,7,8,9,10,11]]
 ]){
  const cards=indices.map(i=>{const [,name,label]=scenes[i];const b64=fs.readFileSync(path.join(__dirname,name+'.png')).toString('base64');return `<section><div class="label"><span>${String(i+1).padStart(2,'0')}</span>${label}</div><img src="data:image/png;base64,${b64}"></section>`}).join('');
  await page.setContent(`<!doctype html><html><head><meta charset="utf-8"><style>*{box-sizing:border-box}body{margin:0;background:#efedf6;color:#262536;font-family:'Microsoft YaHei',sans-serif;padding:42px 42px 28px}header{margin-bottom:28px}h1{font-size:30px;font-weight:500;margin:7px 0 8px}p{font-size:14px;color:#625f70;margin:0}.eyebrow{font-size:12px;letter-spacing:2px;color:#4965a1}.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:30px 26px}.label{font-size:15px;font-weight:500;display:flex;gap:12px;align-items:center;margin-bottom:13px}.label span{color:#4965a1;font-size:12px}img{display:block;width:100%;border-radius:28px}footer{margin-top:26px;color:#6c6879;font-size:12px}</style></head><body><header><div class="eyebrow">CLEARTUNE · PRODUCT DESIGN</div><h1>${title}</h1><p>${subtitle}</p></header><div class="grid">${cards}</div><footer>设计提案 · 演示内容与链接 · 非应用运行截图 · 2026.09.24</footer></body></html>`);
  await page.screenshot({path:path.join(__dirname,file+'.png'),fullPage:true});
 }
 await browser.close();
 console.log('Rendered 13 screens and 2 review boards. Core interactions, draft retention, 320px layout and dark rendering passed.');
})();

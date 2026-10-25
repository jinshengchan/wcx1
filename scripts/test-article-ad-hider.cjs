// Run: node scripts/test-article-ad-hider.cjs
// Execute the shipped JS against deterministic DOM fixtures (no WeChat connection).
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync('app/src/main/java/com/Johnny/wcx/features/items/system/ArticleAdHider.kt', 'utf8');
const script = source.match(/internal val HIDE_JS = """([\s\S]*?)"""\.trimIndent\(\)/)[1];
function fixture(host = 'mp.weixin.qq.com', path = '/s') {
  let observer, tasks = [], listeners = new Map();
  const nodes = [];
  function node(tag, id = '', src = '', display = '') {
    const style = new Map(display ? [['display', [display, '']]] : []);
    const el = {tagName:tag, id, isConnected:true, src,
      getAttribute(k) {return this[k];},
      style:{getPropertyValue:k=>style.get(k)?.[0] || '', getPropertyPriority:k=>style.get(k)?.[1] || '',
        setProperty(k,v,p=''){style.set(k,[v,p]);}, removeProperty(k){style.delete(k);}}};
    nodes.push(el); return el;
  }
  const window = {addEventListener:(k,f)=>listeners.set(k,f), removeEventListener:k=>listeners.delete(k)};
  const location = {hostname:host, pathname:path, search:'?x=1', href:`https://${host}${path}?x=1`};
  const document = {documentElement:{}, querySelectorAll(selector) {
    return nodes.filter(n=>n.isConnected && (selector === 'iframe[src]' ? n.tagName==='IFRAME' && !!n.src : selector.split(',').includes('#'+n.id)));
  }};
  const context = vm.createContext({window, location, document, URL, Map, JSON,
    MutationObserver:class {constructor(cb){this.cb=cb;observer=this;} observe(){this.active=true;} disconnect(){this.active=false;}},
    setTimeout(fn){tasks.push(fn);return tasks.length;},clearTimeout(){tasks=[];}});
  return {node,window,location,run:()=>JSON.parse(vm.runInContext(script,context)),
    mutate(){if(observer?.active)observer.cb();while(tasks.length)tasks.shift()();},
    hidden:el=>el.style.getPropertyValue('display')==='none',
    pagehide(){listeners.get('pagehide')?.();}};
}
const f = fixture();
const body = f.node('DIV','js_content');
const comments = f.node('DIV','js_cmt_area');
const ordinary = f.node('IFRAME','','https://example.com/widget');
const spoof = f.node('IFRAME','','https://example.com/?url=wxa.wxs.qq.com/tmpl/qe/base_tmpl.html');
const boundary = f.node('IFRAME','','https://wxa.wxs.qq.com.evil.test/tmpl/qe/base_tmpl.html');
const ad = f.node('IFRAME','','https://wxa.wxs.qq.com/tmpl/qe/base_tmpl.html');
const footer = f.node('DIV','js_bottom_ad_area','','block');
assert.equal(f.run().hidden,2);
assert.ok(f.hidden(ad) && f.hidden(footer));
for(const el of [body,comments,ordinary,spoof,boundary])assert.ok(!f.hidden(el),'normal content must survive');
const state = f.window.__wcxArticleAdV6;
assert.equal(f.run().hidden,2);assert.equal(f.window.__wcxArticleAdV6,state,'one observer per document');
const lazy = f.node('DIV','js_comment_ad_area');f.mutate();assert.ok(f.hidden(lazy));
const lateSrc=f.node('IFRAME');f.mutate();assert.ok(!f.hidden(lateSrc));
lateSrc.src='https://wxa.wxs.qq.com/tmpl/qe/base_tmpl.html';f.mutate();assert.ok(f.hidden(lateSrc));
ad.style.setProperty('display','block');f.mutate();assert.ok(f.hidden(ad),'page style reset must be handled');
assert.ok(!f.hidden(comments),'comment list must remain');
f.pagehide();assert.equal(f.window.__wcxArticleAdV6,undefined);
assert.equal(footer.style.getPropertyValue('display'),'block','restore original styles');
assert.ok(!f.hidden(ad));
assert.equal(f.run().hidden,4,'back/forward document can reinstall');
f.location.pathname='/search';assert.equal(f.run().article,false);assert.ok(!f.hidden(lazy));
for(const [host,path] of [['example.com','/s'],['mp.weixin.qq.com','/__tmpl__/0.html'],['mp.weixin.qq.com','/search']]) {
  const x=fixture(host,path),n=x.node('DIV','js_bottom_ad_area');assert.equal(x.run().article,false);assert.ok(!x.hidden(n));
}
console.log('PASS: body/footer/lazy comment ads; normal content and host boundaries; reuse, style resets, disable/navigation restoration.');

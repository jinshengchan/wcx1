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
    const el = {tagName:tag, id, isConnected:true, src, className:'', parentElement:null,
      closest(selector) {
        let current=this;
        const m=selector.match(/^(\w+)?\.([\w-]+)$/);
        while(current){
          if(m && (!m[1] || current.tagName.toLowerCase()===m[1]) && current.className.split(/\s+/).includes(m[2])) return current;
          current=current.parentElement;
        }
        return null;
      },
      getAttribute(k) {return this[k];},
      style:{getPropertyValue:k=>style.get(k)?.[0] || '', getPropertyPriority:k=>style.get(k)?.[1] || '',
        setProperty(k,v,p=''){style.set(k,[v,p]);}, removeProperty(k){style.delete(k);}}};
    nodes.push(el); return el;
  }
  const window = {addEventListener:(k,f)=>listeners.set(k,f), removeEventListener:k=>listeners.delete(k)};
  const location = {hostname:host, pathname:path, search:'?x=1', href:`https://${host}${path}?x=1`};
  const head = {appendChild(el){el.parentNode=this;el.isConnected=true;},removeChild(el){el.parentNode=null;el.isConnected=false;}};
  const document = {head, documentElement:{}, createElement(tag){const el=node(tag.toUpperCase());el.isConnected=false;return el;}, querySelectorAll(selector) {
    return nodes.filter(n=>n.isConnected && (selector === 'iframe[src]' ? n.tagName==='IFRAME' && !!n.src : selector.split(',').includes('#'+n.id)));
  }};
  const context = vm.createContext({window, location, document, URL, Map, JSON,
    MutationObserver:class {constructor(cb){this.cb=cb;observer=this;} observe(){this.active=true;} disconnect(){this.active=false;}},
    setTimeout(fn){tasks.push(fn);return tasks.length;},clearTimeout(){tasks=[];}});
  return {node,window,location,styleCount:()=>nodes.filter(n=>n.tagName==='STYLE' && n.isConnected).length,run:()=>JSON.parse(vm.runInContext(script,context)),
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
assert.equal(f.run().hidden,2);assert.equal(f.run().css,true);
assert.ok(f.hidden(ad) && f.hidden(footer));
for(const el of [body,comments,ordinary,spoof,boundary])assert.ok(!f.hidden(el),'normal content must survive');
assert.equal(f.styleCount(),1);
const state = f.window.__wcxArticleAdV7;
assert.equal(f.run().hidden,2);assert.equal(f.window.__wcxArticleAdV7,state,'one observer per document');assert.equal(f.styleCount(),1,'one stylesheet per article');
const lazy = f.node('DIV','js_comment_ad_area');f.mutate();assert.ok(f.hidden(lazy));
const lateSrc=f.node('IFRAME');f.mutate();assert.ok(!f.hidden(lateSrc));
lateSrc.src='https://wxa.wxs.qq.com/tmpl/qe/base_tmpl.html';f.mutate();assert.ok(f.hidden(lateSrc));
ad.style.setProperty('display','block');f.mutate();assert.ok(f.hidden(ad),'page style reset must be handled');
assert.ok(!f.hidden(comments),'comment list must remain');
f.pagehide();assert.equal(f.window.__wcxArticleAdV7,undefined);assert.equal(f.styleCount(),0,'stylesheet removed on stop');
assert.equal(footer.style.getPropertyValue('display'),'block','restore original styles');
assert.ok(!f.hidden(ad));
assert.equal(f.run().hidden,4,'back/forward document can reinstall');
f.location.pathname='/search';assert.equal(f.run().article,false);assert.ok(!f.hidden(lazy));
for(const [host,path] of [['example.com','/s'],['mp.weixin.qq.com','/__tmpl__/0.html'],['mp.weixin.qq.com','/search']]) {
  const x=fixture(host,path),n=x.node('DIV','js_bottom_ad_area');assert.equal(x.run().article,false);assert.ok(!x.hidden(n));
}
// Exact device-log hierarchy, including normal comments sharing the same list.
const g=fixture();
const list=g.node('UL');list.className='discuss_list';
const row=g.node('LI','cidad_comment_constant_key');row.className='js_comment_item discuss_item cidad_comment_constant_key';row.parentElement=list;
const card=g.node('DIV');card.className='comment-ad-container comment-ad-visible';card.parentElement=row;
const media=g.node('DIV');media.className='discuss_media';media.parentElement=card;
const iframe=g.node('IFRAME','','https://wxa.wxs.qq.com/tmpl/qe/base_tmpl.html');iframe.parentElement=media;
const normal=g.node('LI');normal.className='js_comment_item discuss_item';normal.parentElement=list;
const bottomArea=g.node('DIV','page_bottom_area');
const bottom=g.node('DIV');bottom.className='mpda_bottom_container';bottom.parentElement=bottomArea;
const bottomFrame=g.node('IFRAME','','https://wxa.wxs.qq.com/tmpl/qe/base_tmpl.html');bottomFrame.parentElement=bottom;
assert.equal(g.run().hidden,5);
for(const x of [iframe,card,row,bottom,bottomFrame]) assert.ok(g.hidden(x));
for(const x of [normal,list,bottomArea]) assert.ok(!g.hidden(x),'normal comments and bottom region stay visible');
card.style.setProperty('display','block');g.mutate();assert.ok(g.hidden(card));assert.equal(g.run().reapplied,1);
row.isConnected=false;card.isConnected=false;iframe.isConnected=false;g.mutate();assert.ok(!g.hidden(row),'detached nodes restore original styles');
g.pagehide();assert.ok(!g.hidden(bottom));
// Preloaded template becomes a real article in the same document.
const preload=fixture('mp.weixin.qq.com','/__tmpl__/0.html');const nextAd=preload.node('DIV','js_bottom_ad_area');
assert.equal(preload.run().article,false);preload.location.pathname='/s';assert.equal(preload.run().hidden,1);assert.ok(preload.hidden(nextAd));
console.log('PASS: log-confirmed comment row/footer wrappers, normal siblings, preload activation, reappearance counters, restore; body/footer/lazy comment ads; normal content and host boundaries; reuse, style resets, disable/navigation restoration.');

console.log('PASS: CSS installed only in article context; listener/style/inline lifecycle exercised with DOM fixtures (not a rendering browser).');

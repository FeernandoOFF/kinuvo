package kinuvo.ui.artifacts

internal const val DESIGN_STYLE = """html,body{margin:0;height:100%;overflow:hidden;font:13px system-ui,sans-serif}
#s{position:fixed;inset:0;touch-action:none;user-select:none;-webkit-user-select:none;overflow:hidden;cursor:grab}
#w{position:absolute;left:0;top:0;transform-origin:0 0}
.a{position:absolute}
.a iframe{display:block;border:0;border-radius:6px;background:#fff;box-shadow:0 0 0 1px #8884;pointer-events:none}
#o{position:fixed;inset:0;pointer-events:none;overflow:hidden;color:var(--fg)}
.l{position:absolute;left:0;top:0;display:flex;align-items:center;gap:6px;height:22px;white-space:nowrap;font-weight:600;font-size:12px;color:var(--mut)}
.l span:first-child{overflow:hidden;text-overflow:ellipsis}
.l.on{color:#3b82f6}
.l .e{display:none;font-weight:400}
.l.on .e{display:inline}
#sel{position:absolute;display:none;border:2px solid #3b82f6;box-sizing:border-box}
.hd{position:absolute;display:none;width:10px;height:10px;margin:-5px 0 0 -5px;background:#fff;border:2px solid #3b82f6;box-sizing:border-box}
.ui{pointer-events:auto;background:var(--bg);color:var(--fg);border:1px solid var(--line);box-shadow:0 2px 12px #0004}
#tb{position:absolute;left:50%;bottom:16px;transform:translateX(-50%);display:none;gap:2px;padding:3px;border-radius:999px}
#tb button,#fb button{all:unset;cursor:pointer;display:flex;align-items:center;gap:6px;min-width:32px;height:32px;padding:0 10px;box-sizing:border-box;justify-content:center;border-radius:999px;font-weight:600}
#tb button:hover,#fb button:hover{background:var(--hover)}
#fb{position:absolute;display:none;flex-wrap:nowrap;gap:2px;align-items:center;padding:3px;border-radius:12px}
#fb button{min-width:28px;height:28px;padding:0 6px;border-radius:8px}
#fb select,#fb input{font:inherit;height:28px;border:1px solid var(--line);border-radius:8px;background:var(--bg);color:var(--fg);padding:0 4px}
#fb input{width:150px}
#pn{position:absolute;left:8px;right:8px;bottom:8px;max-height:46vh;overflow:auto;display:none;border-radius:16px;padding:10px 12px 12px;box-sizing:border-box;max-width:560px;margin:0 auto}
#pn h4{margin:0;flex:1;font-size:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
#pn .hh{display:flex;align-items:center;margin-bottom:8px}
#pn .hh button{all:unset;cursor:pointer;width:28px;height:28px;text-align:center;line-height:28px;border-radius:999px;font-size:16px}
#pn .hh button:hover{background:var(--hover)}
#pn .g{display:grid;grid-template-columns:1fr 1fr;gap:8px}
#pn label{display:flex;flex-direction:column;gap:3px;font-size:11px;color:var(--mut);min-width:0}
#pn label.w{grid-column:1/3}
#pn input,#pn select{font:13px system-ui,sans-serif;height:32px;box-sizing:border-box;width:100%;border:1px solid var(--line);border-radius:8px;background:var(--bg);color:var(--fg);padding:0 8px;min-width:0}
#pn input[type=color]{padding:2px}"""

private fun theme(dark: Boolean) = if (dark) "--bg:#2b2b28;--fg:#eceae4;--mut:#b4b4b4;--line:#4a4a46;--hover:#3a3a36" else "--bg:#fff;--fg:#222;--mut:#666;--line:#d9d6cf;--hover:#eeece6"

internal fun designCss(background: String, dark: Boolean) = "$DESIGN_STYLE\n:root{${theme(dark)}}html,body{background:$background}"

internal fun designScript(json: String) = "var J=${literal(json)};$DESIGN_SCRIPT"

private const val SLIDERS = """<svg viewBox='0 0 24 24' width='18' height='18' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round'><path d='M4 7h10M18 7h2M4 17h2M10 17h10'/><circle cx='16' cy='7' r='2'/><circle cx='8' cy='17' r='2'/></svg>"""

private const val DESIGN_SCRIPT = """(function(){
var D;try{D=JSON.parse(J)}catch(e){D={}}
var S=document.getElementById("s"),W=document.getElementById("w"),O=document.getElementById("o"),G=48,P=48,MIN=.1,MAX=4,LH=26,HS=16;
var x=0,y=0,k=1,touched=false,P1={},tap=null,last=null,pinch=null,drag=null,sel=null,ed=false,txt=null,pinned=false,timer=null,panelOpen=false,cur=0,saved=null;
function mk(t,c,p){var e=document.createElement(t);if(c)e.className=c;if(p)p.appendChild(e);return e}
var B=(D.artboards||[]).map(function(a){return{a:a,bx:0,by:0,w:a.width,h:a.height,name:a.name||a.id,html:a.html||"",dirty:false,ex:a.x!=null&&a.y!=null}});
B.forEach(function(b){if(b.ex){b.bx=b.a.x;b.by=b.a.y}else{b.bx=cur;b.by=0}cur+=b.w+G});
pinned=B.some(function(b){return b.ex});
B.forEach(function(b){var c=mk("div","a",W),f=mk("iframe","",c),l=mk("div","l",O);
f.setAttribute("sandbox","allow-same-origin");f.srcdoc=b.html;
b.n=mk("span","",l);b.e=mk("span","e",l);b.e.textContent="⤢";
b.c=c;b.f=f;b.l=l;geom(b)});
var se=mk("div","",O),hs=[0,1,2,3].map(function(){var h=mk("div","hd",O);return h}),tb=mk("div","ui",O),fb=mk("div","ui",O),pn=mk("div","ui",O);
se.id="sel";tb.id="tb";fb.id="fb";pn.id="pn";
function geom(b){b.c.style.left=b.bx+"px";b.c.style.top=b.by+"px";b.f.style.width=b.w+"px";b.f.style.height=b.h+"px"}
function doc(b){return b.f.contentDocument}
function bounds(){var a=1e9,t=1e9,r=-1e9,u=-1e9;B.forEach(function(b){a=Math.min(a,b.bx);t=Math.min(t,b.by);r=Math.max(r,b.bx+b.w);u=Math.max(u,b.by+b.h)});return B.length?{l:a,t:t,w:r-a,h:u-t}:{l:0,t:0,w:1,h:1}}
function put(){W.style.transform="translate("+x+"px,"+y+"px) scale("+k+")";draw()}
function fit(){var q=bounds(),vw=innerWidth,vh=innerHeight;k=Math.max(MIN,Math.min((vw-2*P)/q.w,(vh-2*P-LH)/q.h,1));x=(vw-q.w*k)/2-q.l*k;y=(vh-q.h*k)/2-q.t*k+LH/2;put()}
function zoom(px,py,nk){nk=Math.max(MIN,Math.min(MAX,nk));x=px-(px-x)*nk/k;y=py-(py-y)*nk/k;k=nk;put()}
function open(id){parent.postMessage({kinuvo:"design-open",id:id},"*")}
function cs(el){return el.ownerDocument.defaultView.getComputedStyle(el)}
function rectOf(){if(!sel)return null;var b=B[sel.b];if(sel.el){var r=sel.el.getBoundingClientRect();return{l:x+(b.bx+r.left)*k,t:y+(b.by+r.top)*k,w:r.width*k,h:r.height*k}}return{l:x+b.bx*k,t:y+b.by*k,w:b.w*k,h:b.h*k}}
function corners(r){return[[r.l,r.t],[r.l+r.w,r.t],[r.l,r.t+r.h],[r.l+r.w,r.t+r.h]]}
function draw(){
B.forEach(function(b,i){b.l.className="l"+(sel&&sel.b===i?" on":"");b.n.textContent=b.name;b.l.style.transform="translate("+(x+b.bx*k)+"px,"+(y+b.by*k-LH)+"px)";b.l.style.maxWidth=Math.max(70,b.w*k)+"px"});
var r=rectOf();
if(!r){se.style.display="none";hs.forEach(function(h){h.style.display="none"});fb.style.display="none";return}
se.style.cssText="display:block;left:"+r.l+"px;top:"+r.t+"px;width:"+r.w+"px;height:"+r.h+"px";
var c=corners(r),show=!(ed&&!sel.el);
hs.forEach(function(h,i){h.style.display=show?"block":"none";h.style.left=c[i][0]+"px";h.style.top=c[i][1]+"px"});
if(txt){fb.style.display="flex";var w=fb.offsetWidth,hh=fb.offsetHeight,t=r.t-hh-8;if(t<8)t=r.t+r.h+8;fb.style.top=Math.max(8,Math.min(t,innerHeight-hh-8))+"px";fb.style.left=Math.max(8,Math.min(r.l+r.w/2-w/2,innerWidth-w-8))+"px"}else fb.style.display="none"}
function inR(r,px,py){return px>=r.l&&px<=r.l+r.w&&py>=r.t&&py<=r.t+r.h}
function inE(el,px,py){var q=el.getBoundingClientRect();return q.width>0&&px>=q.left-6&&px<=q.right+6&&py>=q.top-6&&py<=q.bottom+6}
function sr(b){return{l:x+b.bx*k,t:y+b.by*k,w:b.w*k,h:b.h*k}}
function elAt(b,px,py){var d=doc(b),el=d&&d.elementFromPoint((px-x)/k-b.bx,(py-y)/k-b.by);return el&&el!==d.documentElement&&el!==d.body?el:null}
function hit(px,py){
var r=rectOf(),i;
if(r&&!(ed&&!sel.el)){var c=corners(r);for(i=0;i<4;i++)if(Math.abs(px-c[i][0])<=HS&&Math.abs(py-c[i][1])<=HS)return{k:"handle",c:i}}
if(sel&&inE(B[sel.b].e,px,py)&&B[sel.b].l.className.indexOf("on")>0)return{k:"expand"};
for(i=B.length-1;i>=0;i--)if(inE(B[i].l,px,py))return{k:"board",i:i};
if(ed&&inR(sr(B[sel.b]),px,py)){var el=elAt(B[sel.b],px,py);return el?{k:"el",i:sel.b,el:el}:{k:"board",i:sel.b}}
for(i=B.length-1;i>=0;i--)if(inR(sr(B[i]),px,py))return{k:"board",i:i};
return{k:"empty"}}
function pts(){return Object.keys(P1).map(function(i){return P1[i]})}
function dist(){var p=pts();return Math.hypot(p[0].x-p[1].x,p[0].y-p[1].y)}
function mid(){var p=pts();return{x:(p[0].x+p[1].x)/2,y:(p[0].y+p[1].y)/2}}
function tr(el){var m=(el.style.translate||"").match(/-?[\d.]+/g)||[];return[+m[0]||0,+m[1]||0]}
function startDrag(m,e,c){var d={m:m,c:c,sx:e.clientX,sy:e.clientY};if(sel.el){var q=sel.el.getBoundingClientRect(),t=tr(sel.el);d.w=q.width;d.h=q.height;d.tx=t[0];d.ty=t[1]}else{var b=B[sel.b];d.w=b.w;d.h=b.h;d.bx=b.bx;d.by=b.by}return d}
function applyDrag(e){var d=drag,dx=(e.clientX-d.sx)/k,dy=(e.clientY-d.sy)/k,b=B[sel.b],el=sel.el,R=Math.round;
if(d.m==="mv"){if(el){el.style.translate=R(d.tx+dx)+"px "+R(d.ty+dy)+"px";b.dirty=true}else{pinned=true;b.bx=R(d.bx+dx);b.by=R(d.by+dy);geom(b)}}
else{var sx=d.c%2?1:-1,sy=d.c<2?-1:1,mn=el?8:40,nw=Math.max(mn,R(d.w+sx*dx)),nh=Math.max(mn,R(d.h+sy*dy));
if(el){var s=el.style;s.boxSizing="border-box";s.width=nw+"px";s.height=nh+"px";if(sx<0||sy<0)s.translate=R(d.tx+(sx<0?d.w-nw:0))+"px "+R(d.ty+(sy<0?d.h-nh:0))+"px";b.dirty=true}
else{pinned=true;b.w=nw;b.h=nh;b.bx=R(d.bx+(sx<0?d.w-nw:0));b.by=R(d.by+(sy<0?d.h-nh:0));geom(b)}}
draw();schedule()}
function snap(){return JSON.stringify({artboards:B.map(function(b){var o={id:b.a.id,name:b.name,width:b.w,height:b.h};if(pinned||b.ex){o.x=b.bx;o.y=b.by}o.html=ser(b);return o})})}
function ser(b){if(!b.dirty)return b.html;var d=doc(b),c=d.documentElement.cloneNode(true);
c.querySelectorAll("[data-kinuvo-editor]").forEach(function(e){e.remove()});
c.querySelectorAll("[contenteditable]").forEach(function(e){e.removeAttribute("contenteditable")});
b.dirty=false;return(d.doctype?"<!DOCTYPE "+d.doctype.name+">":"")+c.outerHTML}
function flush(){timer=null;parent.postMessage({kinuvo:"design-edit",json:snap()},"*")}
function schedule(){clearTimeout(timer);timer=setTimeout(flush,500)}
window.kinuvoFlush=flush;
function inject(b){var d=doc(b),s=d.createElement("style");s.setAttribute("data-kinuvo-editor","");s.textContent="*{-webkit-user-select:none;user-select:none;cursor:default}[contenteditable]{-webkit-user-select:text;user-select:text;cursor:text;outline:none}";(d.head||d.documentElement).appendChild(s)}
function enterEdit(i){ed=true;sel={b:i,el:null};inject(B[i]);refresh()}
function exitEdit(){endText();if(ed){B[sel.b]&&doc(B[sel.b]).querySelectorAll("style[data-kinuvo-editor]").forEach(function(e){e.remove()})}ed=false}
function noNav(e){if(e.target.closest&&e.target.closest("a"))e.preventDefault()}
function onText(){B[sel.b].dirty=true;draw();schedule()}
function startText(el){endText();txt=el;var b=B[sel.b],d=doc(b);el.contentEditable="true";b.f.style.pointerEvents="auto";
d.addEventListener("input",onText);d.addEventListener("keydown",esc);d.addEventListener("click",noNav);b.f.contentWindow.focus();el.focus();draw()}
function endText(){if(!txt)return;var b=B[sel.b],d=doc(b);txt.removeAttribute("contenteditable");b.f.style.pointerEvents="";
d.removeEventListener("input",onText);d.removeEventListener("keydown",esc);d.removeEventListener("click",noNav);txt=null;fb.style.display="none"}
function choose(T){if(T.k==="board"){if(ed&&sel.b!==T.i)exitEdit();sel={b:T.i,el:null}}else if(T.k==="el")sel={b:T.i,el:T.el};else{exitEdit();sel=null;panelOpen=false}refresh()}
function tapped(T,e){var now=Date.now(),dbl=last&&now-last.t<350&&Math.hypot(e.clientX-last.x,e.clientY-last.y)<30;
if(T.k==="expand"){last=null;open(B[sel.b].a.id);return}
if(T.k==="handle")return;
if(dbl){last=null;
if(ed&&T.k==="el"){sel={b:T.i,el:T.el};startText(T.el);refresh();return}
if(!ed&&T.k==="board"&&sel&&sel.b===T.i&&!sel.el){enterEdit(T.i);return}
if(T.k==="empty"&&!ed){if(Math.abs(k-1)<.01)fit();else zoom(e.clientX,e.clientY,1);return}}
else last={t:now,x:e.clientX,y:e.clientY};
choose(T)}
S.addEventListener("pointerdown",function(e){touched=true;endText();try{S.setPointerCapture(e.pointerId)}catch(_){}
P1[e.pointerId]={x:e.clientX,y:e.clientY};
if(Object.keys(P1).length>1){tap=null;drag=null;pinch={d:dist(),k:k};return}
var t=hit(e.clientX,e.clientY);tap={t:t,x:e.clientX,y:e.clientY,at:Date.now(),moved:false};drag=null;
if(t.k==="handle")drag=startDrag("rs",e,t.c);
else if(sel&&(t.k==="el"&&sel.el===t.el||t.k==="board"&&!sel.el&&!ed&&sel.b===t.i))drag=startDrag("mv",e)});
S.addEventListener("pointermove",function(e){var p=P1[e.pointerId];if(!p)return;var n=Object.keys(P1).length;
if(n===2&&pinch){p.x=e.clientX;p.y=e.clientY;var m=mid();zoom(m.x,m.y,pinch.k*dist()/pinch.d);return}
if(n===1){if(tap&&!tap.moved&&Math.hypot(e.clientX-tap.x,e.clientY-tap.y)>6)tap.moved=true;
if(drag){if(tap&&tap.moved)applyDrag(e)}else{x+=e.clientX-p.x;y+=e.clientY-p.y;put()}}
p.x=e.clientX;p.y=e.clientY});
function up(e){if(!P1[e.pointerId])return;delete P1[e.pointerId];
if(pinch){pinch=null;tap=null;drag=null;return}
var t=tap,moved=drag&&t&&t.moved;tap=null;drag=null;if(moved&&panelOpen)build();
if(e.type!=="pointerup"||!t||t.moved||Date.now()-t.at>500)return;tapped(t.t,e)}
S.addEventListener("pointerup",up);S.addEventListener("pointercancel",up);
S.addEventListener("wheel",function(e){e.preventDefault();touched=true;
if(e.ctrlKey||e.metaKey)zoom(e.clientX,e.clientY,k*Math.exp(-e.deltaY*(e.ctrlKey?.01:.0025)));else{x-=e.deltaX;y-=e.deltaY;put()}},{passive:false});
function esc(e){if(e.key!=="Escape")return;if(txt)endText();else if(panelOpen)panelOpen=false;else if(ed)exitEdit();else sel=null;refresh()}
addEventListener("keydown",esc);
addEventListener("resize",function(){if(!touched)fit()});
function btn(label,fn,p){var b=mk("button","",p);b.innerHTML=label;b.onmousedown=function(e){e.preventDefault()};b.onclick=fn;return b}
function toolbar(){tb.innerHTML="";var any=false;
if(sel&&!panelOpen){btn("$SLIDERS",function(){panelOpen=true;refresh()},tb).title="Properties";any=true}
if(ed){btn("Done",function(){exitEdit();sel=null;panelOpen=false;refresh()},tb);any=true}
tb.style.display=any&&!panelOpen?"flex":"none"}
function hex(c,d){var m=/rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?/.exec(c);if(!m||m[4]==="0")return d;return"#"+[1,2,3].map(function(i){return(+m[i]).toString(16).padStart(2,"0")}).join("")}
function fld(p,label,type,get,set,o){var l=mk("label",o&&o.w?"w":"",p),i;l.appendChild(document.createTextNode(label));
if(o&&o.opts){i=mk("select","",l);o.opts.forEach(function(v){var q=mk("option","",i);q.value=v;q.textContent=v});i.value=get()}
else{i=mk("input","",l);i.type=type;if(o&&o.step)i.step=o.step;if(o&&o.min!=null)i.min=o.min;i.value=get()}
i.oninput=function(){var v=type==="number"?parseFloat(i.value):i.value;if(type==="number"&&isNaN(v))return;set(v);draw();schedule()};return i}
function build(){pn.innerHTML="";if(!sel||!panelOpen){pn.style.display="none";return}pn.style.display="block";
var b=B[sel.b],el=sel.el,h=mk("div","hh",pn),g=mk("div","g",pn),R=Math.round;
mk("h4","",h).textContent=el?"<"+el.tagName.toLowerCase()+">":b.name;
var cl=mk("button","",h);cl.textContent="×";cl.onclick=function(){panelOpen=false;refresh()};
if(!el){var bd=doc(b).body;
fld(g,"Name","text",function(){return b.name},function(v){b.name=v},{w:1});
fld(g,"Width","number",function(){return b.w},function(v){b.w=Math.max(40,R(v));pinned=true;geom(b)},{min:40});
fld(g,"Height","number",function(){return b.h},function(v){b.h=Math.max(40,R(v));pinned=true;geom(b)},{min:40});
fld(g,"Background","color",function(){return hex(cs(bd).backgroundColor,"#ffffff")},function(v){bd.style.backgroundColor=v;b.dirty=true},{w:1})}
else{var c=cs(el),st=el.style;
if(!el.children.length)fld(g,"Text","text",function(){return el.textContent},function(v){el.textContent=v;b.dirty=true},{w:1});
fld(g,"Text colour","color",function(){return hex(c.color,"#000000")},function(v){st.color=v;b.dirty=true});
fld(g,"Background","color",function(){return hex(c.backgroundColor,"#ffffff")},function(v){st.backgroundColor=v;b.dirty=true});
fld(g,"Font size","number",function(){return R(parseFloat(c.fontSize))},function(v){st.fontSize=v+"px";b.dirty=true},{min:1});
fld(g,"Weight","select",function(){return String(c.fontWeight)},function(v){st.fontWeight=v;b.dirty=true},{opts:["300","400","500","600","700","800"]});
fld(g,"Padding","number",function(){return R(parseFloat(c.paddingTop))},function(v){st.padding=v+"px";b.dirty=true},{min:0});
fld(g,"Radius","number",function(){return R(parseFloat(c.borderTopLeftRadius))},function(v){st.borderRadius=v+"px";b.dirty=true},{min:0});
fld(g,"Width","number",function(){return R(el.getBoundingClientRect().width)},function(v){st.boxSizing="border-box";st.width=v+"px";b.dirty=true},{min:1});
fld(g,"Height","number",function(){return R(el.getBoundingClientRect().height)},function(v){st.boxSizing="border-box";st.height=v+"px";b.dirty=true},{min:1});
fld(g,"Opacity","number",function(){return c.opacity},function(v){st.opacity=v;b.dirty=true},{step:.05,min:0})}}
function fmt(){fb.innerHTML="";var f=function(){return doc(B[sel.b])},run=function(c,v){f().execCommand(c,false,v);onText()};
var s=mk("select","",fb);[["Paragraph","p"],["Heading 1","h1"],["Heading 2","h2"],["Heading 3","h3"]].forEach(function(o){var q=mk("option","",s);q.textContent=o[0];q.value=o[1]});
s.onchange=function(){run("formatBlock",s.value)};
btn("B",function(){run("bold")},fb).style.fontWeight="700";
btn("I",function(){run("italic")},fb).style.fontStyle="italic";
btn("U",function(){run("underline")},fb).style.textDecoration="underline";
var l=btn("Link",function(){var w=B[sel.b].f.contentWindow,g=w.getSelection(),r=g.rangeCount&&g.getRangeAt(0).cloneRange(),i=mk("input");i.placeholder="https://";
i.onkeydown=function(e){if(e.key==="Enter"){if(r){g.removeAllRanges();g.addRange(r)}if(i.value)run("createLink",i.value);i.replaceWith(l)}else if(e.key==="Escape"){e.stopPropagation();i.replaceWith(l)}};
l.replaceWith(i);i.focus()},fb)}
fmt();
function refresh(){draw();toolbar();build()}
tb.style.display="none";
fit()})();"""

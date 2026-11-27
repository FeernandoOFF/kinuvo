package kinuvo.ui.artifacts

/**
 * Runs in the artifact's frame and does nothing unless the page has a reveal.js deck (`window.Reveal`, found once it is ready). It
 * reports the slide position, the current slide's speaker notes as Markdown and the next slide to the app, and applies positions and
 * steps the app sends. A position it applied is remembered, so reveal's events for it are not reported as changes. In a browser tab,
 * where the page is its own parent, it stays off, so the speaker view there is untouched.
 * Once it syncs, a phone-width deck leaves reveal's scroll view, whose fragments follow the scroll position rather than steps.
 */
internal const val DECK_ADAPTER = """(function(){if(parent===window)return;
var last=null,tries=0;
function key(h,v,f){return h+","+(v||0)+","+(f==null?-1:f)}
function md(html){var d=document.createElement("div");d.innerHTML=String(html);
function inl(n){return Array.prototype.map.call(n.childNodes,node).join("")}
function wrap(m,t){t=t.trim();return t?m+t+m:""}
function node(n){if(n.nodeType===3)return n.nodeValue.replace(/\s+/g," ");if(n.nodeType!==1)return"";var t=n.tagName.toLowerCase();
if(t==="br")return"\\\n";if(t==="strong"||t==="b")return wrap("**",inl(n));if(t==="em"||t==="i")return wrap("_",inl(n));if(t==="code")return wrap("`",n.textContent);
if(t==="a"){var h=n.getAttribute("href");return h?"["+inl(n).trim()+"]("+h+")":inl(n)}
if(/^h[1-6]$/.test(t))return"\n\n"+"#".repeat(+t[1])+" "+inl(n).trim()+"\n\n";
if(t==="ul"||t==="ol"){var i=0;return"\n\n"+Array.prototype.filter.call(n.children,function(c){return c.tagName==="LI"}).map(function(c){i++;return(t==="ol"?i+". ":"- ")+inl(c).trim()}).join("\n")+"\n\n"}
if(t==="blockquote")return"\n\n"+inl(n).trim().split("\n").map(function(l){return"> "+l}).join("\n")+"\n\n";
if(t==="p"||t==="div"||t==="li"||t==="tr")return"\n\n"+inl(n).trim()+"\n\n";return inl(n)}
return inl(d).replace(/[ \t]+\n/g,"\n").replace(/\n{3,}/g,"\n\n").trim()}
function notes(s){if(!s)return"";var n=s.getAttribute("data-notes");if(n)return n.trim();var a=s.querySelectorAll("aside.notes"),t=[];a.forEach(function(e){t.push(/<[a-z][\s\S]*>/i.test(e.innerHTML)?md(e.innerHTML):e.textContent.trim())});return t.join("\n\n")}
function body(s){var c=s.cloneNode(true);c.querySelectorAll("aside.notes,section").forEach(function(e){e.remove()});return c}
function summary(s){if(!s)return null;var c=body(s),h=c.querySelector("h1,h2,h3,h4,h5,h6"),title=h?h.textContent.trim():"";if(h)h.remove();
var text=c.textContent.replace(/\s+/g," ").trim();return{title:title,text:text.slice(0,140)}}
function slides(){var r=[];Reveal.getHorizontalSlides().forEach(function(s){var v=s.querySelectorAll(":scope > section");if(v.length)v.forEach(function(x){r.push(x)});else r.push(s)});return r}
function report(ready){var i=Reveal.getIndices(),k=key(i.h,i.v,i.f),all=slides(),cur=Reveal.getCurrentSlide(),at=all.indexOf(cur),changed=!ready&&k!==last;last=k;
parent.postMessage({kinuvo:"deck",h:i.h,v:i.v||0,f:i.f==null?null:i.f,n:at+1,total:all.length,notes:notes(cur).slice(0,20000),next:summary(all[at+1]),ready:!!ready,changed:changed},"*")}
function slideMode(){if(typeof Reveal.isScrollView==="function"&&Reveal.isScrollView())Reveal.configure({scrollActivationWidth:0})}
function start(){["slidechanged","fragmentshown","fragmenthidden"].forEach(function(e){Reveal.on(e,function(){report(false)})});
addEventListener("message",function(e){var d=e.data;if(e.source!==parent||!d)return;
if(d.kinuvo==="deck-apply"||d.kinuvo==="deck-step")slideMode();
if(d.kinuvo==="deck-apply"){var f=d.f==null?undefined:d.f;last=key(d.h,d.v,f);var i=Reveal.getIndices();if(key(i.h,i.v,i.f)!==last)Reveal.slide(d.h,d.v||0,f)}
else if(d.kinuvo==="deck-step"){if(d.forward)Reveal.next();else Reveal.prev()}});
report(true)}
function wait(){if(window.Reveal&&typeof Reveal.getIndices==="function"&&(typeof Reveal.isReady!=="function"||Reveal.isReady()))return start();if(++tries<150)setTimeout(wait,200)}
if(document.readyState==="loading")addEventListener("DOMContentLoaded",wait);else wait()})();"""

/** What the page may send and the app's way to reach it: the same two wrappers around the frame the design canvas uses. */
internal fun deckWrapper() = "<script>$BRIDGE$RECEIVE</script>"

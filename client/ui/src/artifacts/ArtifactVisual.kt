package kinuvo.ui.artifacts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlin.random.Random

/** [html] editable in place; the artifact's own scripts and handlers are blocked, and each edit comes back through [onEdit] as the whole page. */
@Composable
internal fun VisualEditor(html: String, modifier: Modifier = Modifier, onEdit: (String) -> Unit) = WebView(remember(html) { visualDocument(html) }, modifier, onEdit)

internal fun newNonce() = Random.nextBytes(16).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

private val DOCTYPE = Regex("^\\s*<!doctype[^>]*>", RegexOption.IGNORE_CASE)

internal fun literal(text: String) = buildString {
    append('"')
    for (c in text) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '<' -> append("\\u003c")
        '\u2028' -> append("\\u2028")
        '\u2029' -> append("\\u2029")
        else -> append(c)
    }
    append('"')
}

/** Only the editor's own script, marked with a nonce the artifact can't know, may run in the frame. */
internal fun visualDocument(html: String, nonce: String = newNonce()): String {
    val doctype = DOCTYPE.find(html)?.value?.trim() ?: "<!doctype html>"
    val inner = sandboxed(html, "'nonce-$nonce'") + "<style data-kinuvo-editor>$EDITOR_STYLE</style><script data-kinuvo-editor nonce=\"$nonce\">${editorScript(doctype)}</script>"
    return framed(inner, "<script>$BRIDGE</script>")
}

internal const val BRIDGE = """addEventListener("message",function(e){var d=e.data,f=document.getElementById("f");if(e.source!==f.contentWindow||!d)return;
var m=d.kinuvo==="artifact-edit"&&typeof d.html==="string"?d.html:d.kinuvo==="design-open"&&typeof d.id==="string"?JSON.stringify({kinuvo:d.kinuvo,id:d.id}):d.kinuvo==="design-edit"&&typeof d.json==="string"?JSON.stringify({kinuvo:d.kinuvo,json:d.json}):null;if(m===null)return;
if(window.kinuvoEdit)window.kinuvoEdit(m);else if(window.Kinuvo&&Kinuvo.edit)Kinuvo.edit(m);else if(window.webkit&&webkit.messageHandlers&&webkit.messageHandlers.kinuvo)webkit.messageHandlers.kinuvo.postMessage(m)})"""

private const val EDITOR_STYLE = """body{outline:none}
#kinuvo-bar{--bg:#fff;--fg:#222;--line:#d9d6cf;--hover:#eeece6;position:fixed;display:none;flex-wrap:wrap;gap:2px;max-width:calc(100vw - 16px);padding:3px;background:var(--bg);color:var(--fg);border:1px solid var(--line);border-radius:10px;box-shadow:0 2px 10px #0003;font:13px system-ui,sans-serif;z-index:2147483647}
@media(prefers-color-scheme:dark){#kinuvo-bar{--bg:#2b2b28;--fg:#eceae4;--line:#4a4a46;--hover:#3a3a36}}
#kinuvo-bar>div{display:flex;gap:2px;align-items:center;width:100%}
#kinuvo-bar button{all:unset;cursor:pointer;min-width:26px;height:26px;text-align:center;line-height:26px;border-radius:6px}
#kinuvo-bar button:hover{background:var(--hover)}
#kinuvo-bar select,#kinuvo-bar input{font:inherit;height:26px;border:1px solid var(--line);border-radius:6px;background:var(--bg);color:var(--fg);padding:0 4px}
#kinuvo-bar input{width:170px;padding:0 6px}"""

private fun editorScript(doctype: String) = """(function(){
var DT=${literal(doctype)},b=document.body,t,saved,bar=document.createElement("div"),main=document.createElement("div"),more=document.createElement("div"),sel=document.createElement("select"),l,m;
b.contentEditable="true";
bar.id="kinuvo-bar";bar.setAttribute("data-kinuvo-editor","");bar.contentEditable="false";
function send(){clearTimeout(t);t=setTimeout(function(){var c=document.documentElement.cloneNode(true);
c.querySelectorAll("[data-kinuvo-editor]").forEach(function(e){e.remove()});
var cb=c.querySelector("body");if(cb)cb.removeAttribute("contenteditable");
parent.postMessage({kinuvo:"artifact-edit",html:DT+c.outerHTML},"*")},300)}
function restore(){var s=getSelection();if(saved){s.removeAllRanges();s.addRange(saved)}}
function run(c,v){restore();document.execCommand(c,false,v);send()}
function block(){restore();var n=getSelection().anchorNode;n=n&&(n.nodeType===1?n:n.parentNode);var e=n&&n.closest("p,h1,h2,h3,h4,h5,h6,li,blockquote,pre,figure,img,table");if(e&&e!==b){e.remove();bar.style.display="none";send()}}
function link(){if(!saved)return;var i=document.createElement("input");i.placeholder="https://";
i.onkeydown=function(e){if(e.key==="Enter"){if(i.value)run("createLink",i.value);i.replaceWith(l)}else if(e.key==="Escape")i.replaceWith(l)};
l.replaceWith(i);i.focus()}
function btn(label,fn,row){var x=document.createElement("button");x.textContent=label;x.onmousedown=function(e){e.preventDefault()};x.onclick=fn;row.appendChild(x);return x}
[["Paragraph","p"],["Heading 1","h1"],["Heading 2","h2"],["Heading 3","h3"]].forEach(function(o){var x=document.createElement("option");x.textContent=o[0];x.value=o[1];sel.appendChild(x)});
sel.onchange=function(){run("formatBlock",sel.value)};
main.appendChild(sel);
btn("B",function(){run("bold")},main).style.fontWeight="700";
btn("I",function(){run("italic")},main).style.fontStyle="italic";
btn("U",function(){run("underline")},main).style.textDecoration="underline";
l=btn("Link",link,main);
m=btn("⋯",function(){more.style.display=more.style.display==="flex"?"none":"flex"},main);
more.style.display="none";
btn("List",function(){run("insertUnorderedList")},more);btn("Delete block",block,more);btn("Undo",function(){run("undo")},more);btn("Redo",function(){run("redo")},more);
bar.appendChild(main);bar.appendChild(more);b.appendChild(bar);
document.addEventListener("selectionchange",function(){var s=getSelection();
if(!s.rangeCount||!b.contains(s.anchorNode)){if(!bar.contains(document.activeElement))bar.style.display="none";return}
if(bar.contains(s.anchorNode))return;
var r=s.getRangeAt(0),q=r.getClientRects()[0]||r.getBoundingClientRect();
if(!q||(!q.width&&!q.height&&!q.top)){var n=s.anchorNode;q=(n.nodeType===1?n:n.parentNode).getBoundingClientRect()}
saved=r.cloneRange();
var v=(document.queryCommandValue("formatBlock")||"").toLowerCase();sel.value=["h1","h2","h3"].indexOf(v)<0?"p":v;
bar.style.display="flex";var w=bar.offsetWidth,h=bar.offsetHeight,y=q.top-h-8;if(y<8)y=q.bottom+8;
bar.style.top=Math.max(8,Math.min(y,innerHeight-h-8))+"px";
bar.style.left=Math.max(8,Math.min(q.left+q.width/2-w/2,innerWidth-w-8))+"px"});
b.addEventListener("input",function(e){if(!bar.contains(e.target))send()});
b.addEventListener("click",function(e){if(e.target.closest&&e.target.closest("a"))e.preventDefault()});
})();"""

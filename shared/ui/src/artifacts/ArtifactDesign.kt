package kinuvo.ui.artifacts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.protocol.Artboard
import kinuvo.ui.design.*

/** The [json] design's artboards on a pan and zoom canvas; tapping an artboard's label reports its id through [onOpen]. */
@Composable
internal fun DesignCanvas(json: String, background: Color, modifier: Modifier = Modifier, onOpen: ((String) -> Unit)? = null) =
    WebView(remember(json, background) { designDocument(json, background = background) }, modifier, onOpen)

private fun Color.css() = "rgb(${(red * 255).toInt()},${(green * 255).toInt()},${(blue * 255).toInt()})"

/** One trusted page: the canvas script is the only script allowed, and the artboards are script-less read-only frames. */
internal fun designDocument(json: String, nonce: String = newNonce(), background: Color = Color(0xFF1E1E1E)): String {
    val label = if (background.luminance() > 0.5f) "#555" else "#b4b4b4"
    val inner = sandboxed(
        "<body><div id=\"s\"><div id=\"w\"></div></div><style>$DESIGN_STYLE" +
            "html,body{background:${background.css()}}#s{color:$label}</style><script nonce=\"$nonce\">${designScript(json)}</script></body>",
        "'nonce-$nonce'",
    )
    return framed(inner, "<script>$BRIDGE</script>" + topFade(background))
}

private const val DESIGN_STYLE = """html,body{margin:0;height:100%;overflow:hidden}
#s{position:fixed;inset:0;touch-action:none;user-select:none;-webkit-user-select:none;overflow:hidden;font:13px system-ui,sans-serif;cursor:grab}
#w{position:absolute;left:0;top:0;transform-origin:0 0}
.a{position:absolute;top:0}
.l{position:absolute;left:0;bottom:100%;width:100%;margin-bottom:6px;display:flex;align-items:center;gap:6px;white-space:nowrap;cursor:pointer}
.l span{overflow:hidden;text-overflow:ellipsis;font-weight:600}
.l b{font-weight:400;opacity:.7}
.a iframe{display:block;border:1px solid #8884;border-radius:6px;background:#fff;box-sizing:content-box;margin:-1px;pointer-events:none}"""

private fun designScript(json: String) = """(function(){
var D;try{D=JSON.parse(${literal(json)})}catch(e){D={}}
var S=document.getElementById("s"),W=document.getElementById("w"),B=D.artboards||[],G=48,P=48,LH=28,MIN=.1,MAX=4;
var x=0,y=0,k=1,X=0,H=0,touched=false,P1={},tap=null,last=null,pinch=null;
B.forEach(function(a){var c=document.createElement("div"),l=document.createElement("div"),n=document.createElement("span"),o=document.createElement("b"),f=document.createElement("iframe");
c.className="a";c.style.left=X+"px";l.className="l";l.setAttribute("data-id",a.id);n.textContent=a.name||a.id;o.textContent="⤢";
l.appendChild(n);l.appendChild(o);
f.setAttribute("sandbox","allow-same-origin");f.style.width=a.width+"px";f.style.height=a.height+"px";f.srcdoc=a.html||"";
c.appendChild(l);c.appendChild(f);W.appendChild(c);X+=a.width+G;H=Math.max(H,a.height)});
X=Math.max(X-G,1);H=Math.max(H,1);
function put(){W.style.transform="translate("+x+"px,"+y+"px) scale("+k+")"}
function fit(){var vw=innerWidth,vh=innerHeight;k=Math.max(MIN,Math.min((vw-2*P)/X,(vh-2*P)/(H+LH),1));x=(vw-X*k)/2;y=(vh-(H+LH)*k)/2+LH*k;put()}
function zoom(px,py,nk){nk=Math.max(MIN,Math.min(MAX,nk));x=px-(px-x)*nk/k;y=py-(py-y)*nk/k;k=nk;put()}
function open(id){parent.postMessage({kinuvo:"design-open",id:id},"*")}
function dist(){var p=Object.keys(P1).map(function(i){return P1[i]});return Math.hypot(p[0].x-p[1].x,p[0].y-p[1].y)}
function mid(){var p=Object.keys(P1).map(function(i){return P1[i]});return{x:(p[0].x+p[1].x)/2,y:(p[0].y+p[1].y)/2}}
S.addEventListener("pointerdown",function(e){touched=true;try{S.setPointerCapture(e.pointerId)}catch(_){}
P1[e.pointerId]={x:e.clientX,y:e.clientY};var n=Object.keys(P1).length;
if(n===1){var l=e.target.closest&&e.target.closest(".l");tap={id:l&&l.getAttribute("data-id"),x:e.clientX,y:e.clientY,t:Date.now(),moved:false}}
else{tap=null;pinch={d:dist(),k:k}}});
S.addEventListener("pointermove",function(e){var p=P1[e.pointerId];if(!p)return;var n=Object.keys(P1).length;
if(n===1){var dx=e.clientX-p.x,dy=e.clientY-p.y;x+=dx;y+=dy;put();if(tap&&Math.hypot(e.clientX-tap.x,e.clientY-tap.y)>6)tap.moved=true}
p.x=e.clientX;p.y=e.clientY;
if(n===2&&pinch){var m=mid();zoom(m.x,m.y,pinch.k*dist()/pinch.d)}});
function up(e){if(!P1[e.pointerId])return;delete P1[e.pointerId];pinch=null;
if(e.type==="pointerup"&&tap&&!tap.moved&&Date.now()-tap.t<500){
if(tap.id){open(tap.id)}
else if(last&&Date.now()-last.t<350&&Math.hypot(e.clientX-last.x,e.clientY-last.y)<30){last=null;if(Math.abs(k-1)<.01)fit();else zoom(e.clientX,e.clientY,1)}
else last={t:Date.now(),x:e.clientX,y:e.clientY}}
tap=null}
S.addEventListener("pointerup",up);S.addEventListener("pointercancel",up);
S.addEventListener("wheel",function(e){e.preventDefault();touched=true;
if(e.ctrlKey||e.metaKey)zoom(e.clientX,e.clientY,k*Math.exp(-e.deltaY*(e.ctrlKey?.01:.0025)));else{x-=e.deltaX;y-=e.deltaY;put()}},{passive:false});
addEventListener("resize",function(){if(!touched)fit()});
fit();
})();"""

private val Artboard.label get() = name.ifBlank { id }

/** A single artboard at its real size, shrunk to the page's width when wider. */
internal fun artboardDocument(board: Artboard, background: Color): String =
    "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>" +
        "<body style=\"margin:0;background:${background.css()}\"><div id=\"o\" style=\"overflow:hidden\"><div id=\"c\" style=\"transform-origin:0 0;width:${board.width}px;height:${board.height}px\">" +
        "<iframe sandbox=\"allow-scripts\" referrerpolicy=\"no-referrer\" style=\"border:0;width:100%;height:100%;display:block\" srcdoc=\"${attr(sandboxed(board.html))}\"></iframe></div></div>" +
        "<script>var c=document.getElementById('c'),o=document.getElementById('o');function r(){var s=Math.min(1,innerWidth/${board.width});c.style.transform='scale('+s+')';o.style.width=${board.width}*s+'px';o.style.height=${board.height}*s+'px'}addEventListener('resize',r);r()</script></body></html>"

/** One artboard of [boards] full screen; [present] adds previous and next. */
@Composable
internal fun ArtboardViewer(boards: List<Artboard>, index: Int, present: Boolean, background: Color, onIndex: (Int) -> Unit, onClose: () -> Unit) {
    val board = boards.getOrNull(index) ?: return
    val density = LocalDensity.current
    var controls by remember { mutableStateOf(ControlsHeight) }
    Box(Modifier.fillMaxSize().background(background)) {
        WebView(remember(board, background) { artboardDocument(board, background) }, Modifier.fillMaxSize().padding(top = controls))
        val chrome = LocalWindowChrome.current
        Row(
            Modifier.fillMaxWidth().onSizeChanged { controls = with(density) { it.height.toDp() } }
                .padding(chrome?.padding(true, true) ?: PaddingValues()).padding(horizontal = Spacing.space10, vertical = Spacing.space8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.space8),
        ) {
            FloatingBar { FloatingButton(Lucide.X, if (present) "Exit" else "Close", onClose) }
            FloatingBar {
                Text(board.label, Modifier.padding(horizontal = Spacing.space10).widthIn(max = 220.dp), fontWeight = FontWeight.SemiBold, singleLine = true, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.weight(1f))
            if (present) FloatingBar {
                FloatingButton(Lucide.ChevronLeft, "Previous") { if (index > 0) onIndex(index - 1) }
                Text("${index + 1} / ${boards.size}", Modifier.padding(horizontal = Spacing.space4), style = TextStyles.secondary, color = color(mutedColor))
                FloatingButton(Lucide.ChevronRight, "Next") { if (index < boards.lastIndex) onIndex(index + 1) }
            }
        }
    }
}

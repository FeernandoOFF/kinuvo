package kinuvo.harness.browser

import kotlinx.serialization.json.JsonPrimitive

object BrowserDom {
    private fun quote(value: String) = JsonPrimitive(value).toString()
    private val properties = """() => JSON.stringify([...document.querySelectorAll('input,textarea,select,option,[contenteditable]')].map(e => [e.value,e.checked,e.selected,e.selectedIndex,e.readOnly,e.disabled,e.isContentEditable]))"""

    fun action(revision: String, ref: String, text: String? = null): String {
        val operation = if (text == null) "el.click();" else """
            if(el.readOnly||el.matches(':disabled'))throw Error('This element is not editable');
            const tag=el.tagName.toLowerCase();
            if(!el.isContentEditable&&!['input','textarea'].includes(tag))throw Error('This element cannot be filled');
            if(tag==='input'&&!['text','search','tel','url','email','password','number'].includes(el.type))throw Error('This input cannot be filled');
            el.focus();
            if(el.isContentEditable){
              const range=document.createRange();range.selectNodeContents(el);
              const selection=getSelection();selection.removeAllRanges();selection.addRange(range);
              if(!document.execCommand('insertText',false,${quote(text.orEmpty())}))throw Error('This element did not accept text');
            }else{
            const setter=Object.getOwnPropertyDescriptor(el.tagName==='INPUT'?HTMLInputElement.prototype:HTMLTextAreaElement.prototype,'value').set;
            setter.call(el,${quote(text.orEmpty())});
            el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));
            }
        """.trimIndent()
        return """(() => {
            const snapshot=window.__kinuvoBrowser;
            if(!snapshot||snapshot.revision!==${quote(revision)}||snapshot.version!==snapshot.observed||snapshot.url!==location.href)throw Error('Stale ref; capture again');
            if(snapshot.properties!==($properties)())throw Error('Form state changed; capture again');
            const el=snapshot.refs[${quote(ref)}];
            if(!el||!el.isConnected||!el.getClientRects().length||el.disabled)throw Error('Unknown or stale ref');
            const r=el.getBoundingClientRect();
            if(JSON.stringify([r.x,r.y,r.width,r.height])!==snapshot.rects[${quote(ref)}])throw Error('Element geometry changed; capture again');
            snapshot.revision='';
            $operation
            return 'done';
        })()"""
    }

    fun capture(revision: String): String = """(() => {
            let s=window.__kinuvoBrowser;
            if(!s){s={version:0};window.__kinuvoBrowser=s;new MutationObserver(()=>s.version++).observe(document,{subtree:true,childList:true,attributes:true,characterData:true});}
            s.observed=s.version;s.url=location.href;s.revision=${quote(revision)};
            s.properties=($properties)();s.refs={};s.rects={};
            const elements=[...document.querySelectorAll('a,button,input,textarea,select,[role],[contenteditable=true]')].filter(e=>e.getClientRects().length&&getComputedStyle(e).visibility!=='hidden').slice(0,300).map((e,i)=>{
              const ref='e'+(i+1);s.refs[ref]=e;const r=e.getBoundingClientRect();s.rects[ref]=JSON.stringify([r.x,r.y,r.width,r.height]);
              return {ref,role:e.getAttribute('role')||e.tagName.toLowerCase(),label:(e.getAttribute('aria-label')||e.getAttribute('placeholder')||[...(e.labels||[])].map(l=>l.innerText).join(' ')||e.innerText||'').slice(0,180)};
            });
            return JSON.stringify({elements,text:(document.body?.innerText||'').slice(0,24000)});
        })()"""
}

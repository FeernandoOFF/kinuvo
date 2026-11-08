package kinuvo.harness.browser

import kotlinx.serialization.json.JsonPrimitive

object BrowserDom {
    private fun quote(value: String) = JsonPrimitive(value).toString()
    private val identity = """e => ({role:e.getAttribute('role')||e.tagName.toLowerCase(),label:(e.getAttribute('aria-label')||e.getAttribute('placeholder')||(e.labels instanceof NodeList?[...e.labels]:[]).map(l=>l.innerText).join(' ')||e.innerText||'').slice(0,180)})"""

    fun action(revision: String, ref: String, text: String? = null, trusted: Boolean = false): String {
        if (text == null) return operation(revision, ref, "snapshot.revision='';el.click();")
        return operation(revision, ref, """
            if(el.readOnly||el.matches(':disabled'))throw Error('Not performed: this element is not editable');
            const tag=el.tagName.toLowerCase(),text=${quote(text)};
            if(!el.isContentEditable&&!['input','textarea','select'].includes(tag))throw Error('Not performed: this element cannot be filled');
            if(tag==='input'&&!['text','search','tel','url','email','password','number'].includes(el.type))throw Error('Not performed: this input cannot be filled');
            let option;
            if(tag==='select'){
              const options=[...el.options],normalize=s=>s.trim().toLowerCase();
              option=options.find(o=>o.label===text||o.value===text)||options.find(o=>normalize(o.label)===normalize(text)||normalize(o.value)===normalize(text));
              if(!option)throw Error('Not performed: no matching option. Available options: '+options.map(o=>o.label+' ('+o.value+')').join(', '));
              if(option.disabled||option.parentElement.disabled)throw Error('Not performed: this option is disabled');
            }
            snapshot.revision='';snapshot.pending={el,expected:option?option.value:text};
            el.focus();
            if(tag==='select'){
              el.selectedIndex=option.index;
              el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));
            }else{
              if(el.isContentEditable){
                const range=document.createRange();range.selectNodeContents(el);
                const selection=getSelection();selection.removeAllRanges();selection.addRange(range);
              }else el.select();
              if(document.activeElement!==el)throw Error('Not performed: element could not be focused');
              if(${trusted})return 'text';
              if(el.isContentEditable){
                if(!document.execCommand('insertText',false,text))throw Error('This element did not accept text');
              }else{
                const setter=Object.getOwnPropertyDescriptor(tag==='input'?HTMLInputElement.prototype:HTMLTextAreaElement.prototype,'value').set;
                setter.call(el,text);
                el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));
              }
            }
            el.blur();
        """.trimIndent())
    }

    fun commit(): String = """(() => {window.__kinuvoBrowser?.pending?.el.blur();return 'done';})()"""

    fun verify(): String = """(() => {
        const pending=window.__kinuvoBrowser?.pending;
        if(!pending)throw Error('Typed value could not be verified; page changed');
        const {el,expected}=pending;delete window.__kinuvoBrowser.pending;
        const value=el.isContentEditable?el.textContent:el.value;
        if(!el.isConnected||value!==expected)throw Error('Value did not stick; typed but reverted. Current value: '+(el.type==='password'?'[hidden]':JSON.stringify(value)));
        return 'done';
    })()"""

    fun point(revision: String, ref: String): String = operation(revision, ref,
        "snapshot.revision='';el.scrollIntoView({block:'center',inline:'center'});const box=el.getBoundingClientRect();return JSON.stringify({x:box.x+box.width/2,y:box.y+box.height/2});")

    private fun operation(revision: String, ref: String, operation: String): String = """(() => {
            const snapshot=window.__kinuvoBrowser;
            if(!snapshot||snapshot.revision!==${quote(revision)}||snapshot.url!==location.href)throw Error('Not performed: stale ref; capture again');
            const el=snapshot.refs[${quote(ref)}];
            if(!el||!el.isConnected||!el.getClientRects().length||el.matches(':disabled')||getComputedStyle(el).visibility==='hidden'||getComputedStyle(el).display==='none')throw Error('Not performed: element is disconnected, hidden or disabled; capture again');
            if(JSON.stringify(($identity)(el))!==snapshot.identities[${quote(ref)}])throw Error('Not performed: element role or label changed; capture again');
            const r=el.getBoundingClientRect(),before=snapshot.rects[${quote(ref)}];
            if([r.x,r.y,r.width,r.height].some((v,i)=>Math.abs(v-before[i])>8))throw Error('Not performed: element geometry changed; capture again');
            $operation
            return 'done';
        })()"""

    fun capture(revision: String): String = """(() => {
            const s={url:location.href,revision:${quote(revision)},refs:{},rects:{},identities:{}};window.__kinuvoBrowser=s;
            const elements=[...document.querySelectorAll('a,button,input,textarea,select,[role],[contenteditable=true]')].filter(e=>e.getClientRects().length&&getComputedStyle(e).visibility!=='hidden').slice(0,300).map((e,i)=>{
              const ref='e'+(i+1);s.refs[ref]=e;const r=e.getBoundingClientRect();s.rects[ref]=[r.x,r.y,r.width,r.height];
              const identity=($identity)(e);s.identities[ref]=JSON.stringify(identity);
              const state={};
              if(e.type!=='password'&&'value' in e)state.value=e.value;
              if(e.isContentEditable)state.value=e.textContent;
              if(e.tagName==='SELECT')state.options=[...e.options].map(o=>o.label);
              if(e.matches('input[type=checkbox],input[type=radio]'))state.checked=e.checked;
              return {ref,...identity,...state};
            });
            return JSON.stringify({elements,text:(document.body?.innerText||'').slice(0,24000)});
        })()"""

    fun failure(message: String?): String {
        val reason = message.orEmpty().ifBlank { "Browser action failed" }
        val rejection = reason.lineSequence().first().removePrefix("Error: ").takeIf { it.startsWith("Not performed:") }
        return rejection?.substringAfter("Not performed:")?.let { "Not performed:$it" }
            ?: "Browser action failed; outcome may be unknown. Capture and inspect before continuing. $reason"
    }
}

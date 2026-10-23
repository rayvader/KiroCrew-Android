package io.github.rayvader.kirocrew.core

/** Scripts the app runs inside the dashboard page. Plain strings so they can be tested. */
object Scripts {

    const val BRIDGE = "KiroCrewAndroid"

    /** Message channel for notifications. WebView only gives it to frames from the saved servers. */
    const val NOTIFY_CHANNEL = "KiroCrewNotify"

    /**
     * WebView has no `window.Notification`, so the dashboard's own toasts
     * ("chat finished", new bell notes) would do nothing. This stand-in sends
     * them to the app, which posts real Android notifications.
     */
    val NOTIFY_SHIM = """
        (function(){
          var P=window.$NOTIFY_CHANNEL;
          if(!P||window.__kcaNotify)return;window.__kcaNotify=1;
          var A=window.$BRIDGE,cache='default',waiters=[];
          var send=function(o){try{P.postMessage(JSON.stringify(o));}catch(e){}};
          P.addEventListener('message',function(ev){
            var m;try{m=JSON.parse(ev.data);}catch(e){return;}
            if(!m||m.op!=='permission')return;
            cache=String(m.value);var w=waiters;waiters=[];
            w.forEach(function(f){try{f(cache);}catch(e){}});
          });
          var perm=function(){
            try{if(A&&A.notificationPermission)cache=String(A.notificationPermission());}catch(e){}
            return cache;
          };
          function N(title,o){
            if(!(this instanceof N))throw new TypeError("Failed to construct 'Notification': Please use the 'new' operator.");
            o=o||{};
            this.title=String(title);
            this.body=o.body==null?'':String(o.body);
            this.tag=o.tag==null?'':String(o.tag);
            this.data=o.data===undefined?null:o.data;
            this.icon=o.icon||'';this.silent=!!o.silent;
            this.onclick=null;this.onclose=null;this.onerror=null;this.onshow=null;
            if(perm()==='granted')send({op:'notify',title:this.title.slice(0,200),body:this.body.slice(0,1000),tag:this.tag.slice(0,200)});
          }
          N.prototype.close=function(){send({op:'close',tag:this.tag});};
          N.prototype.addEventListener=function(){};
          N.prototype.removeEventListener=function(){};
          N.prototype.dispatchEvent=function(){return true;};
          Object.defineProperty(N,'permission',{get:perm,enumerable:true});
          N.maxActions=0;
          N.requestPermission=function(cb){
            return new Promise(function(resolve){
              var done=function(p){if(typeof cb==='function'){try{cb(p);}catch(e){}}resolve(p);};
              var now=perm();
              if(now!=='default'){done(now);return;}
              waiters.push(done);send({op:'request'});
            });
          };
          try{Object.defineProperty(window,'Notification',{value:N,writable:true,configurable:true});}
          catch(e){window.Notification=N;}
        })();
    """.trimIndent()

    /** Adds, updates or removes the user's custom stylesheet. */
    fun customCss(css: String): String =
        "(function(){var id='kirocrew-android-css';var el=document.getElementById(id);" +
            "var css=${JsQuote.str(css)};" +
            "if(!css){if(el)el.remove();return;}" +
            "if(!el){el=document.createElement('style');el.id=id;(document.head||document.documentElement).appendChild(el);}" +
            "el.textContent=css;})();"

    /** Desktop mode: widen the layout viewport so the page renders its desktop layout. */
    const val DESKTOP_VIEWPORT =
        "(function(){var m=document.querySelector('meta[name=viewport]');" +
            "if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}" +
            "m.content='width=1280';})();"

    /**
     * Tells the app each time the page title changes. The dashboard renames its
     * tab after the session or page on every in-app switch, without loading a
     * new page, and WebView does not always report those changes by itself.
     */
    val TITLE_WATCH = """
        (function(){
          var A=window.$BRIDGE;
          if(!A||!A.pageTitle||window.__kcaTitle)return;window.__kcaTitle=1;
          var last=null;
          var send=function(){var t=document.title||'';if(t===last)return;last=t;try{A.pageTitle(t);}catch(e){}};
          new MutationObserver(send).observe(document.head||document.documentElement,{subtree:true,childList:true,characterData:true});
          send();
        })();
    """.trimIndent()

    /**
     * Makes blob:/data: downloads work. WebView cannot hand those URLs to the
     * system downloader, so the page reads the bytes and passes them to the app.
     */
    val DOWNLOAD_HOOKS = """
        (function(){
          if(window.__kcaHooks||!window.$BRIDGE)return;window.__kcaHooks=1;
          var save=function(href,name){
            fetch(href).then(function(r){return r.blob();}).then(function(b){
              var fr=new FileReader();
              fr.onloadend=function(){$BRIDGE.saveDataUrl(String(fr.result),name||'download');};
              fr.onerror=function(){$BRIDGE.downloadFailed('read error');};
              fr.readAsDataURL(b);
            }).catch(function(e){$BRIDGE.downloadFailed(String(e));});
          };
          window.__kcaSave=save;
          var local=function(h){return /^(blob|data):/i.test(h||'');};
          var rv=URL.revokeObjectURL;
          URL.revokeObjectURL=function(){var a=arguments;setTimeout(function(){rv.apply(URL,a);},60000);};
          var oc=HTMLAnchorElement.prototype.click;
          HTMLAnchorElement.prototype.click=function(){
            if(this.hasAttribute('download')&&local(this.href)){save(this.href,this.getAttribute('download'));return;}
            return oc.apply(this,arguments);
          };
          document.addEventListener('click',function(e){
            var a=e.target&&e.target.closest?e.target.closest('a[download]'):null;
            if(a&&local(a.href)){e.preventDefault();e.stopPropagation();save(a.href,a.getAttribute('download'));}
          },true);
        })();
    """.trimIndent()

    fun fetchBlob(url: String, name: String): String =
        "(function(){if(window.__kcaSave){window.__kcaSave(${JsQuote.str(url)},${JsQuote.str(name)});return 'ok';}return 'no';})()"

    /**
     * Moves the single-page app to [url] without a full reload, so the live
     * connection stays open. Returns 'ok', 'same' or 'no' (caller then loads the URL).
     */
    fun spaNavigate(url: String): String =
        "(function(){try{var u=new URL(${JsQuote.str(url)},location.href);" +
            "if(u.origin!==location.origin)return 'no';" +
            "if(u.pathname+u.search+u.hash===location.pathname+location.search+location.hash)return 'same';" +
            "history.pushState({},'',u.href);" +
            "window.dispatchEvent(new PopStateEvent('popstate',{state:history.state}));return 'ok';" +
            "}catch(e){return 'no';}})()"
}

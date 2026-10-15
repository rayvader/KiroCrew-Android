package io.github.rayvader.kirocrew.core

/** Scripts the app runs inside the dashboard page. Plain strings so they can be tested. */
object Scripts {

    const val BRIDGE = "KiroCrewAndroid"

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

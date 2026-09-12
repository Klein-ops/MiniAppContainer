(function () {
  if (window.__MiniAppBridge) return;
  var seq = 0;
  var cbs = {};

  function genId() { return 'r' + (seq++); }

  var B = {
    _cbs: cbs,
    __resolve: function (id, ok, result) {
      var c = cbs[id];
      if (!c) return;
      delete cbs[id];
      try {
        if (ok) c.resolve(result);
        else c.reject(new Error(typeof result === 'string' ? result : String(result)));
      } catch (e) { console.error('bridge callback error', e); }
    },
    call: function (method, params) {
      return new Promise(function (resolve, reject) {
        var id = genId();
        cbs[id] = { resolve: resolve, reject: reject };
        try {
          if (!window.MiniAppNative || typeof MiniAppNative.call !== 'function') {
            delete cbs[id];
            reject(new Error('MiniAppNative bridge not ready'));
            return;
          }
          MiniAppNative.call(id, method, JSON.stringify(params || {}));
        } catch (e) {
          delete cbs[id];
          reject(e);
        }
      });
    }
  };

  window.__MiniAppBridge = B;

  window.MiniApp = {
    call: function (m, p) { return B.call(m, p); },
    info: function () { return B.call('app.info'); },
    system: function () { return B.call('system.info'); },
    toast: function (msg) { return B.call('ui.toast', { message: String(msg) }); },
    fs: {
      read: function (p) { return B.call('fs.read', { path: p }); },
      readBytes: function (p) { return B.call('fs.readBytes', { path: p }); },
      write: function (p, content) { return B.call('fs.write', { path: p, content: content }); },
      writeBytes: function (p, b64) { return B.call('fs.writeBytes', { path: p, base64: b64 }); },
      list: function (p) { return B.call('fs.list', { path: p }); },
      exists: function (p) { return B.call('fs.exists', { path: p }); },
      stat: function (p) { return B.call('fs.stat', { path: p }); },
      mkdir: function (p) { return B.call('fs.mkdir', { path: p }); },
      remove: function (p) { return B.call('fs.remove', { path: p }); }
    },
    wasm: {
      // 旧路径：wasm3 JNI（简单函数，仅字符串参数，保留兼容）
      load: function (p) { return B.call('wasm.load', { path: p }); },
      call: function (handle, func, args) { return B.call('wasm.call', { handle: handle, func: func, args: args || [] }); },
      unload: function (handle) { return B.call('wasm.unload', { handle: handle }); },

      // 新路径：WebAssembly 原生（高性能，支持二进制/Memory/import）
      instantiate: async function(path, imports) {
        try {
          const resp = await fetch(path);
          if (!resp.ok) throw new Error('WASM load failed: HTTP ' + resp.status);
          const bytes = await resp.arrayBuffer();
          const { instance } = await WebAssembly.instantiate(bytes, imports || {});
          return instance;
        } catch (e) {
          console.error('[MiniApp.wasm] instantiate failed:', e);
          throw e;
        }
      },

      createMemory: function(initial, maximum) {
        return new WebAssembly.Memory({
          initial: initial || 256,
          maximum: maximum || 16384
        });
      }
    },
    net: { get: function (u) { return B.call('net.httpGet', { url: u }); } },
    sys: { openUrl: function (u) { return B.call('sys.openUrl', { url: u }); } },
    permission: { request: function (scope) { return B.call('perm.request', { scope: scope }); } }
  };

  console.log('[MiniApp] bridge ready');
})();

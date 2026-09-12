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
      remove: function (p) { return B.call('fs.remove', { path: p }); },
      // SAF 导入导出（无需权限）
      importFile: function (destPath) { return B.call('fs.importFile', { destPath: destPath }); },
      exportFile: function (path) { return B.call('fs.exportFile', { path: path }); },
      // 静默读写内部储存（需 fs.external 权限 + 系统所有文件访问）
      readExternalFile: function (absPath) { return B.call('fs.readExternalFile', { path: absPath }); },
      writeExternalFile: function (absPath, base64) { return B.call('fs.writeExternalFile', { path: absPath, base64: base64 }); }
    },
    wasm: {
      // 利用 WebView 内置 WebAssembly JIT 引擎（高性能，支持二进制/Memory/import）
      instantiate: async function(pathOrBytes, imports) {
        try {
          let bytes;
          if (typeof pathOrBytes === 'string') {
            // 路径模式：fetch（注意：file:// 下可能被 WebView 拦截，见开发者手册 6.3）
            const resp = await fetch(pathOrBytes);
            if (!resp.ok) throw new Error('WASM load failed: HTTP ' + resp.status);
            bytes = await resp.arrayBuffer();
          } else if (pathOrBytes && (typeof pathOrBytes === 'object') && (pathOrBytes.buffer instanceof ArrayBuffer || pathOrBytes instanceof Uint8Array || pathOrBytes instanceof Uint32Array)) {
            // 直接传入 ArrayBuffer / TypedArray（推荐，避免 file:// fetch 问题）
            bytes = pathOrBytes;
          } else {
            throw new Error('instantiate: 需要路径字符串或 ArrayBuffer/TypedArray');
          }
          const { instance } = await WebAssembly.instantiate(bytes, imports || {});
          return instance;
        } catch (e) {
          console.error('[MiniApp.wasm] instantiate failed:', e);
          throw e;
        }
      },

      createMemory: function(initial, maximum) {
        const mem = new WebAssembly.Memory({
          initial: initial || 256,
          maximum: maximum || 16384
        });
        // 返回标准 Memory 对象（控制台可能显示为 {}，但 .buffer 可正常访问）
        return mem;
      }
    },
    net: { get: function (u) { return B.call('net.httpGet', { url: u }); } },
    sys: { openUrl: function (u) { return B.call('sys.openUrl', { url: u }); } },
    permission: { request: function (scope) { return B.call('perm.request', { scope: scope }); } }
  };

  console.log('[MiniApp] bridge ready');
})();

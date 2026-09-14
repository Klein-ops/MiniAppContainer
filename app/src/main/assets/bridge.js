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
    ui: {
      toast: function (msg) { return B.call('ui.toast', { message: String(msg) }); }
    },
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
      writeExternalFile: function (absPath, base64) { return B.call('fs.writeExternalFile', { path: absPath, base64: base64 }); },
      listExternal: function (dir) { return B.call('fs.listExternal', { dir: dir }); },
      existsExternal: function (p) { return B.call('fs.existsExternal', { path: p }); },
      statExternal: function (p) { return B.call('fs.statExternal', { path: p }); },
      mkdirExternal: function (dir) { return B.call('fs.mkdirExternal', { dir: dir }); },
      removeExternal: function (p) { return B.call('fs.removeExternal', { path: p }); },
      renameExternal: function (from, to) { return B.call('fs.renameExternal', { from: from, to: to }); }
    },
    wasm: {
      // 利用 WebView 内置 WebAssembly JIT 引擎（高性能，支持二进制/Memory/import）
      instantiate: async function(pathOrBytes, imports) {
        try {
          let bytes;
          if (typeof pathOrBytes === 'string') {
            // 路径模式：通过 Bridge 读取字节（file:// 下 fetch 会被沙箱拦截，故不走 fetch）；
            // 路径相对沙箱根，如 'app/heavy.wasm'
            const b64 = await B.call('fs.readBytes', { path: pathOrBytes });
            const bin = atob(b64);
            bytes = new Uint8Array(bin.length);
            for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
          } else if (pathOrBytes && (typeof pathOrBytes === 'object') && (pathOrBytes.buffer instanceof ArrayBuffer || pathOrBytes instanceof Uint8Array || pathOrBytes instanceof Uint32Array)) {
            // 直接传入 ArrayBuffer / TypedArray（推荐）
            bytes = pathOrBytes;
          } else {
            throw new Error('instantiate: 需要路径字符串或 ArrayBuffer/TypedArray');
          }
          const { instance } = await WebAssembly.instantiate(bytes, imports || {});
          console.log('[MiniApp.wasm] instantiated');
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
    net: {
      get: function (u) { return B.call('net.httpRequest', { method: 'GET', url: u }); },
      post: function (u, body) { return B.call('net.httpRequest', { method: 'POST', url: u, body: body }); },
      put: function (u, body) { return B.call('net.httpRequest', { method: 'PUT', url: u, body: body }); },
      'delete': function (u) { return B.call('net.httpRequest', { method: 'DELETE', url: u }); },
      request: function (method, url, opts) {
        opts = opts || {};
        return B.call('net.httpRequest', {
          method: method, url: url,
          headers: opts.headers, body: opts.body
        });
      }
    },
    clipboard: {
      read: function () { return B.call('cb.read'); },
      write: function (text) { return B.call('cb.write', { text: String(text) }); }
    },
    notification: {
      show: function (title, body) { return B.call('notify.show', { title: String(title), body: String(body) }); },
      cancel: function () { return B.call('notify.cancel'); }
    },
    dex: {
      // 在隔离进程执行 Dex 字节码（需 dex 权限）
      run: function (opts) {
        opts = opts || {};
        return B.call('dex.run', {
          dex: opts.dex, className: opts.className,
          methodName: opts.methodName || 'run',
          params: opts.params, input: opts.input, output: opts.output
        });
      }
    },
    sys: { openUrl: function (u) { return B.call('sys.openUrl', { url: u }); } },
    permission: { request: function (scope) { return B.call('perm.request', { scope: scope }); } }
  };

  console.log('[MiniApp] bridge ready');
})();

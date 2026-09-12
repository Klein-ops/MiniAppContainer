(function () {
  var out = document.getElementById('out');
  function log(msg) {
    if (typeof msg === 'object') {
      try { msg = JSON.stringify(msg, null, 2); } catch (e) { msg = String(msg); }
    }
    out.textContent = String(msg);
  }
  function run(promiseFactory) {
    Promise.resolve().then(promiseFactory).then(log, function (e) {
      log('错误: ' + (e && e.message ? e.message : e));
    });
  }
  function waitForBridge(maxMs) {
    maxMs = maxMs || 3000;
    return new Promise(function (resolve, reject) {
      var t0 = Date.now();
      (function check() {
        if (window.MiniApp && window.__MiniAppBridge) return resolve();
        if (Date.now() - t0 > maxMs) return reject(new Error('bridge not ready'));
        setTimeout(check, 50);
      })();
    });
  }
  var wasmInstance = null;
  function ensureWasm() {
    if (wasmInstance) return Promise.resolve(wasmInstance);
    return MiniApp.wasm.instantiate('sample.wasm').then(function (inst) { wasmInstance = inst; return inst; });
  }
  var actions = {
    info: function () { return MiniApp.info(); },
    system: function () { return MiniApp.system(); },
    write: function () {
      return MiniApp.fs.write('data/test.txt', '你好，沙箱 ' + new Date().toISOString()).then(function () {
        return MiniApp.call('ui.toast', { message: '已写入' }).then(function () { return '已写入 data/test.txt'; });
      });
    },
    read: function () { return MiniApp.fs.read('data/test.txt'); },
    list: function () { return MiniApp.fs.list('data'); },
    'wasm-add': function () {
      return ensureWasm().then(function (inst) { return inst.exports.add(2, 3); })
        .then(function (r) { return 'add(2,3) = ' + r; });
    },
    'wasm-fib': function () {
      return ensureWasm().then(function (inst) { return inst.exports.fib(15); })
        .then(function (r) { return 'fib(15) = ' + r; });
    },
    net: function () {
      return MiniApp.net.get('https://example.com')
        .then(function (r) { return 'HTTP ' + r.status + ' (body length ' + (r.body ? r.body.length : 0) + ')'; });
    },
    openurl: function () { return MiniApp.sys.openUrl('https://example.com').then(function () { return '已尝试打开链接'; }); },
    perm: function () { return MiniApp.permission.request('net').then(function (ok) { return 'net 授权: ' + (ok ? '是' : '否'); }); }
  };
  document.querySelector('.grid').addEventListener('click', function (e) {
    var t = e.target.closest('button'); if (!t) return;
    var act = t.getAttribute('data-act');
    if (actions[act]) run(actions[act]);
  });
  waitForBridge().then(function () { run(actions.info); }, function (e) { log('桥未就绪: ' + e.message); });
})();

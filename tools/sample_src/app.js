(function () {
  var out = document.getElementById('out');
  function show(msg) {
    if (typeof msg === 'object') {
      try { msg = JSON.stringify(msg, null, 2); } catch (e) { msg = String(msg); }
    }
    out.textContent = String(msg);
  }
  function run(factory) {
    Promise.resolve().then(factory).then(show, function (e) {
      show('错误: ' + (e && e.message ? e.message : e));
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
  function ask(title, def) {
    var v = prompt(title, def);
    return (v == null) ? null : v;
  }
  function b64ToText(b64) {
    var bin = atob(b64);
    try { return decodeURIComponent(escape(bin)); } catch (e) { return null; }
  }
  function textToB64(s) {
    return btoa(unescape(encodeURIComponent(s)));
  }

  var wasm = null;
  function ensureWasm() {
    if (wasm) return Promise.resolve(wasm);
    return MiniApp.wasm.instantiate('app/sample.wasm').then(function (inst) { wasm = inst; return inst; });
  }

  var actions = {
    // ---- 应用与系统 ----
    info: function () { return MiniApp.info(); },
    system: function () { return MiniApp.system(); },
    'open-url': function () {
      var u = ask('要打开的网址', 'https://example.com');
      if (u == null) return '已取消';
      return MiniApp.sys.openUrl(u).then(function () { return '正在打开: ' + u; });
    },

    // ---- 沙箱文件 ----
    write: function () {
      return MiniApp.fs.write('data/test.txt', '你好，沙箱 ' + new Date().toISOString())
        .then(function () { return MiniApp.toast('已写入'); })
        .then(function () { return '已写入 data/test.txt'; });
    },
    read: function () { return MiniApp.fs.read('data/test.txt'); },
    list: function () { return MiniApp.fs.list('data'); },
    stat: function () { return MiniApp.fs.stat('data/test.txt'); },
    exists: function () {
      return MiniApp.fs.exists('data/test.txt').then(function (v) { return 'data/test.txt 存在: ' + v; });
    },
    'chunk-write': function () {
      var content = ask('分块写入的内容', '第一块\n');
      if (content == null) return '已取消';
      return MiniApp.fs.writeChunk('data/chunk.txt', 0, textToB64(content))
        .then(function () { return MiniApp.fs.writeChunk('data/chunk.txt', -1, textToB64('第二块（追加）\n')); })
        .then(function () { return '已分块写入 data/chunk.txt（offset 0 覆盖 + offset -1 追加）'; });
    },
    'chunk-read': function () {
      var off = parseInt(ask('读取偏移', '0'), 10);
      if (isNaN(off)) return '已取消';
      var len = parseInt(ask('读取长度（-1 到文件尾）', '16'), 10);
      if (isNaN(len)) return '已取消';
      return MiniApp.fs.readChunk('data/chunk.txt', off, len).then(function (b64) {
        var text = b64ToText(b64);
        return 'readChunk(offset=' + off + ', length=' + len + ') = ' + JSON.stringify(text);
      });
    },
    append: function () {
      var s = ask('要追加的内容', '追加行 ' + new Date().toTimeString().slice(0, 8));
      if (s == null) return '已取消';
      return MiniApp.fs.append('data/test.txt', textToB64(s + '\n')).then(function () { return '已追加到 data/test.txt'; });
    },
    truncate: function () {
      var size = parseInt(ask('截断到多少字节', '0'), 10);
      if (isNaN(size)) return '已取消';
      return MiniApp.fs.truncate('data/test.txt', size).then(function () { return '已截断 data/test.txt 到 ' + size + ' 字节'; });
    },
    'stream-write': function () {
      var s = ask('流式写入的内容（自动分块 64KB）', '流式写入 ' + new Date().toISOString());
      if (s == null) return '已取消';
      var bytes = new TextEncoder().encode(s);
      return MiniApp.fs.writeStream('data/stream.bin', bytes, 64).then(function () {
        return 'writeStream 写入 ' + bytes.length + ' 字节（分块 64B 演示）';
      });
    },
    'stream-read': function () {
      return MiniApp.fs.readStream('data/stream.bin').then(function (bytes) {
        var text = new TextDecoder().decode(bytes);
        return 'readStream 读到 ' + bytes.length + ' 字节\n' + text;
      });
    },
    grep: function () {
      var pat = ask('grep 搜索模式（普通文本）', '沙箱');
      if (pat == null) return '已取消';
      return MiniApp.fs.grep('data/test.txt', pat, { ignoreCase: true }).then(function (r) {
        return 'grep "' + pat + '" 命中 ' + r.length + ' 行:\n' + JSON.stringify(r, null, 2);
      });
    },
    sed: function () {
      var script = ask('sed 脚本（如 s/你好/您好/g 或 1d）', 's/沙箱/蜗壳/g');
      if (script == null) return '已取消';
      return MiniApp.fs.sed('data/test.txt', script).then(function (out) {
        return 'sed "' + script + '" 结果:\n' + out;
      });
    },
    'import': function () {
      var dest = ask('导入目标（沙箱内路径）', 'data/imported.txt');
      if (dest == null) return '已取消';
      return MiniApp.fs.importFile(dest).then(function (ok) {
        return 'SAF 导入: ' + (ok ? '成功 → ' + dest : '用户取消');
      });
    },
    export: function () {
      var src = ask('导出源（沙箱内路径）', 'data/test.txt');
      if (src == null) return '已取消';
      return MiniApp.fs.exportFile(src).then(function (ok) {
        return 'SAF 导出: ' + (ok ? '成功' : '用户取消');
      });
    },

    // ---- 外部文件（需 fs.external）----
    listExt: function () {
      var dir = ask('要列出的内部储存目录', '/storage/emulated/0');
      if (dir == null) return '已取消';
      return MiniApp.fs.listExternal(dir);
    },
    readExt: function () {
      var p = ask('要读取的外部文件（base64 方式）', '/storage/emulated/0/Documents/test.txt');
      if (p == null) return '已取消';
      return MiniApp.fs.readExternalFile(p).then(function (b64) {
        var text = b64ToText(b64);
        return '读取 ' + b64.length + ' 字符 base64\n' +
          (text != null ? '文本内容:\n' + text.slice(0, 400) : '（二进制内容，已省略）');
      });
    },
    openExt: function () {
      var p = ask('要流式读取的外部文件（授权 URL + fetch）', '/storage/emulated/0/Documents/test.txt');
      if (p == null) return '已取消';
      return MiniApp.fs.openExternalFile(p).then(function (url) {
        return fetch(url).then(function (r) { return r.text(); }).then(function (body) {
          return '授权 URL: ' + url + '\nHTTP 流式读取 ' + body.length + ' 字符\n\n' +
            body.slice(0, 400) + (body.length > 400 ? '…' : '');
        });
      });
    },
    writeExt: function () {
      var p = ask('要写入的外部文件（绝对路径）', '/storage/emulated/0/Documents/wk.txt');
      if (p == null) return '已取消';
      var content = ask('写入内容', '外部写入 ' + new Date().toISOString());
      if (content == null) return '已取消';
      return MiniApp.fs.writeExternalFile(p, textToB64(content))
        .then(function () { return '已写入外部文件: ' + p; });
    },
    renameExt: function () {
      var from = ask('源文件路径', '/storage/emulated/0/Documents/wk.txt');
      if (from == null) return '已取消';
      var to = ask('目标文件路径', '/storage/emulated/0/Documents/wk2.txt');
      if (to == null) return '已取消';
      return MiniApp.fs.renameExternal(from, to).then(function () { return '已重命名: ' + from + ' → ' + to; });
    },

    // ---- WASM ----
    'wasm-add': function () {
      return ensureWasm().then(function (inst) { return 'WASM add(2,3) = ' + inst.exports.add(2, 3); });
    },
    'wasm-fib': function () {
      return ensureWasm().then(function (inst) { return 'WASM fib(15) = ' + inst.exports.fib(15); });
    },

    // ---- 网络（需 net）----
    net: function () {
      return MiniApp.net.get('https://example.com').then(function (r) {
        return 'HTTP ' + r.status + '（body ' + (r.body ? r.body.length : 0) + ' 字符）';
      });
    },
    'net-post': function () {
      var url = ask('POST 目标 URL', 'https://httpbin.org/post');
      if (url == null) return '已取消';
      var body = ask('请求体', '{"hello":"蜗壳"}');
      if (body == null) return '已取消';
      return MiniApp.net.request('POST', url, {
        headers: { 'Content-Type': 'application/json', 'X-Custom': 'showcase' },
        body: body
      }).then(function (r) {
        return 'POST ' + r.status + '\n自定义头: Content-Type / X-Custom\n返回体:\n' +
          String(r.body || '').slice(0, 400);
      });
    },
    'net-download': function () {
      var url = ask('下载 URL', 'https://example.com/');
      if (url == null) return '已取消';
      var dest = ask('目标沙箱路径', 'data/downloaded.html');
      if (dest == null) return '已取消';
      return MiniApp.net.download(url, dest).then(function (r) {
        return '下载直落: ' + r.path + '\nsize=' + r.size + ' bytes, status=' + r.status;
      });
    },
    'net-upload': function () {
      var url = ask('上传目标 URL', 'https://httpbin.org/post');
      if (url == null) return '已取消';
      var src = ask('源沙箱文件', 'data/test.txt');
      if (src == null) return '已取消';
      return MiniApp.net.upload(url, src, { method: 'POST', headers: { 'Content-Type': 'text/plain' } })
        .then(function (r) { return '上传 ' + src + ' → ' + url + '\nstatus=' + r.status + ', body=' + String(r.body || '').slice(0, 200); });
    },

    // ---- 剪贴板（需 clipboard）----
    'cb-read': function () {
      return MiniApp.clipboard.read().then(function (t) { return '剪贴板: ' + t; });
    },
    'cb-write': function () {
      var t = ask('写入剪贴板的内容', '蜗壳示例 ' + new Date().toLocaleTimeString());
      if (t == null) return '已取消';
      return MiniApp.clipboard.write(t).then(function () { return '已写入剪贴板: ' + t; });
    },

    // ---- 通知（需 notification）----
    notify: function () {
      return MiniApp.notification.show('蜗壳示例', '这条通知来自示例小程序')
        .then(function () { return '已发送通知'; });
    },
    'notify-cancel': function () {
      return MiniApp.notification.cancel().then(function () { return '已取消通知'; });
    },

    // ---- 网络存储（需 storage 权限 + 已配置 WebDAV）----
    'storage-list': function () {
      return MiniApp.storage.list('').then(function (r) { return r; });
    },
    'storage-upload': function () {
      var path = ask('WebDAV 路径', 'upload/test.txt');
      if (path == null) return '已取消';
      var content = ask('上传内容', 'WebDAV 上传 ' + new Date().toISOString());
      if (content == null) return '已取消';
      return MiniApp.storage.upload(path, textToB64(content)).then(function (r) {
        return '已上传 → ' + path + '\n返回: ' + JSON.stringify(r);
      });
    },
    'storage-download': function () {
      var path = ask('WebDAV 路径', 'upload/test.txt');
      if (path == null) return '已取消';
      return MiniApp.storage.download(path).then(function (b64) {
        var text = b64ToText(b64);
        if (text != null) return '已下载 ' + path + '\n内容: ' + text.slice(0, 400);
        return '已下载 ' + path + '（' + b64.length + ' 字符 base64，非文本）';
      });
    },
    'storage-delete': function () {
      var path = ask('要删除的 WebDAV 路径', 'upload/test.txt');
      if (path == null) return '已取消';
      return MiniApp.storage.delete(path).then(function (r) { return '已删除 ' + path + ' → ' + JSON.stringify(r); });
    },

    // ---- 小程序调试（debug.*）----
    'debug-log': function () {
      var msg = ask('要写入的调试日志', '示例日志 ' + new Date().toTimeString().slice(0, 8));
      if (msg == null) return '已取消';
      return MiniApp.debug.log(msg).then(function () { return '已写日志（宿主自动打 appKey 标签）'; });
    },
    'debug-logs': function () {
      var n = parseInt(ask('读取最近多少条', '20'), 10);
      if (isNaN(n)) return '已取消';
      return MiniApp.debug.getLogs(n).then(function (r) {
        return '本小程序日志 ' + r.length + ' 条:\n' + JSON.stringify(r, null, 2);
      });
    },
    'debug-clear': function () {
      return MiniApp.debug.clear().then(function () { return '已清空本小程序日志'; });
    },
    'debug-enabled': function () {
      return MiniApp.debug.enabled().then(function (v) { return '调试模式开启: ' + v; });
    },

    // ---- Dex 隔离进程执行（无需权限）----
    'dex-info': function () {
      return MiniApp.dex.run({
        dex: 'app/demo.dex', className: 'com.miniapp.demo.Demo',
        params: { action: 'info' }
      });
    },
    'dex-fib': function () {
      return MiniApp.dex.run({
        dex: 'app/demo.dex', className: 'com.miniapp.demo.Demo',
        params: { action: 'fib', n: 30 }
      });
    },
    'dex-reverse': function () {
      var s = ask('要反转的字符串（交给隔离进程处理）', '蜗壳 Dex 隔离进程');
      if (s == null) return '已取消';
      return MiniApp.dex.run({
        dex: 'app/demo.dex', className: 'com.miniapp.demo.Demo',
        params: { action: 'reverse', s: s }
      });
    },

    // ---- 设备控制 ----
    vibrate: function () {
      return MiniApp.sys.vibrate(300).then(function () { return '已震动 300ms'; });
    },
    flashlight: function () {
      return MiniApp.sys.flashlight(true).then(function () { return '手电筒已打开'; });
    },
    'flashlight-off': function () {
      return MiniApp.sys.flashlight(false).then(function () { return '手电筒已关闭'; });
    },
    orientation: function () {
      return MiniApp.sys.setOrientation('landscape').then(function () { return '已切换横屏'; });
    },
    portrait: function () {
      return MiniApp.sys.setOrientation('portrait').then(function () { return '已切回竖屏'; });
    },
    statusbar: function () {
      return MiniApp.sys.setStatusBarColor('#2563EB').then(function () { return '状态栏颜色已设为 #2563EB'; });
    },
    selection: function () {
      return MiniApp.sys.setTextSelection(true).then(function () { return '已允许长按文本选择'; });
    },

    // ---- 权限 ----
    perm: function () {
      return MiniApp.permission.request('net').then(function (ok) { return 'net 授权: ' + (ok ? '是' : '否'); });
    }
  };

  document.querySelector('main').addEventListener('click', function (e) {
    var b = e.target.closest('button'); if (!b) return;
    var act = b.getAttribute('data-act');
    if (actions[act]) run(actions[act]);
  });

  waitForBridge().then(function () { run(actions.info); }, function (e) { show('桥未就绪: ' + e.message); });
})();

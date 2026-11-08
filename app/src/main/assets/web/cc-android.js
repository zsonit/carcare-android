/* 车管家 Android 原生桥接（随 index.html 一起在 <head> 前注入）
 * 作用：把 WebView 缺失/受限的浏览器能力交给原生实现，
 *       保证与 iOS 版、浏览器版功能表现一致。
 * 消息协议与 iOS 版 bridge.js 完全相同。
 */
(function () {
  var N = window.CarCareAndroid;
  if (!N || window.__ccAndroidBridge) { return; }
  window.__ccAndroidBridge = true;

  function post(payload) {
    try { N.post(JSON.stringify(payload)); } catch (e) {}
  }
  window.__ccNative = post;

  function styleTag(id) {
    var el = document.getElementById(id);
    if (!el) {
      el = document.createElement('style');
      el.id = id;
      (document.head || document.documentElement).appendChild(el);
    }
    return el;
  }

  /* 1) 本身就是独立 App：隐藏「安装到手机」卡片 */
  function hideInstall() {
    try { styleTag('cc-install').textContent = '#installCard{display:none!important}'; } catch (e) {}
  }
  hideInstall();
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', hideInstall);
  }

  /* 2) 安全区：Android WebView 里 env(safe-area-inset-*) 恒为 0，
        改由原生把状态栏/导航栏高度换算成 CSS px 传进来 */
  function applyInsets() {
    var raw = '0,0';
    try { raw = N.getInsets() || '0,0'; } catch (e) {}
    var parts = String(raw).split(',');
    var top = parseFloat(parts[0]) || 0;
    var bottom = parseFloat(parts[1]) || 0;
    try {
      styleTag('cc-insets').textContent =
        'header{padding-top:calc(9px + ' + top + 'px)!important}' +
        'body{padding-bottom:calc(96px + ' + bottom + 'px)!important}' +
        '.fab{bottom:calc(96px + ' + bottom + 'px)!important}' +
        'nav{bottom:calc(8px + ' + bottom + 'px)!important}' +
        '.toast{bottom:calc(104px + ' + bottom + 'px)!important}' +
        '.modal .sheet{padding-bottom:calc(20px + ' + bottom + 'px)!important}';
    } catch (e) {}
  }
  window.__ccApplyInsets = applyInsets;
  applyInsets();
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', applyInsets);
  }

  /* 3) 系统通知：转成原生通知（需 Android 13+ 的运行时授权） */
  var perm = 'default';
  try { perm = String(N.notificationState ? N.notificationState() : 'default'); } catch (e) { perm = 'default'; }
  var waiters = [];

  function settle(p) {
    perm = p;
    var list = waiters;
    waiters = [];
    for (var i = 0; i < list.length; i++) {
      try { list[i](p); } catch (e) {}
    }
  }
  window.__ccNotifyPermission = settle;

  function CCNotification(title, options) {
    options = options || {};
    post({
      type: 'notify',
      title: title == null ? '' : String(title),
      body: options.body == null ? '' : String(options.body)
    });
  }
  try {
    Object.defineProperty(CCNotification, 'permission', {
      get: function () { return perm; }
    });
  } catch (e) {
    CCNotification.permission = 'default';
  }
  CCNotification.requestPermission = function (callback) {
    var p = new Promise(function (resolve) {
      waiters.push(resolve);
      try {
        post({ type: 'notifyPermission' });
      } catch (e) {
        resolve('denied');
      }
    });
    if (typeof callback === 'function') { p.then(callback); }
    return p;
  };
  try {
    Object.defineProperty(window, 'Notification', {
      value: CCNotification, writable: true, configurable: true
    });
  } catch (e) {
    try { window.Notification = CCNotification; } catch (e2) {}
  }

  /* 4) window.open('') 的两种用法：导出 PDF（写整页 HTML）、查看凭证图 */
  var origOpen = window.open;
  window.open = function (url) {
    if (url && String(url).length && String(url) !== 'about:blank') {
      try { return origOpen.apply(window, arguments); } catch (e) { return null; }
    }
    var buffer = { html: '' };
    var done = false;

    function flush() {
      if (done) { return; }
      var html = buffer.html;
      if (!html || !html.trim().length) { return; }
      done = true;
      var trimmed = html.trim();
      var imgMatch = trimmed.match(/^<img[^>]*src="([^"]+)"[^>]*>$/i);
      if (imgMatch && imgMatch[1].indexOf('data:') === 0) {
        post({ type: 'previewImage', dataURL: imgMatch[1] });
        return;
      }
      var titleMatch = html.match(/<title>([^<]*)<\/title>/i);
      var name = (titleMatch && titleMatch[1] ? titleMatch[1].trim() : '维保记录') + '.pdf';
      post({ type: 'sharePDF', html: html, name: name });
    }

    var fakeDoc = {
      write: function (s) { buffer.html += String(s); },
      writeln: function (s) { buffer.html += String(s) + '\n'; },
      open: function () {},
      close: function () { flush(); },
      getElementById: function () { return null; }
    };
    var fakeWin = {
      document: fakeDoc,
      print: flush,
      focus: function () {},
      close: flush,
      location: { href: '' }
    };
    setTimeout(flush, 180);
    setTimeout(flush, 1500);
    return fakeWin;
  };

  /* 5) 导出 CSV：<a download href="blob:..."> 在 WebView 里不会触发下载，
        改为读出文本交给原生保存并分享 */
  if (window.HTMLAnchorElement) {
    var origClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () {
      try {
        var name = this.getAttribute('download');
        var href = this.href || '';
        if (name && href.indexOf('blob:') === 0) {
          fetch(href).then(function (r) { return r.text(); }).then(function (text) {
            post({ type: 'shareFile', name: name, text: text });
          }).catch(function () {});
          return;
        }
      } catch (e) {}
      return origClick.apply(this, arguments);
    };
  }

  /* 6) 页面资源都在 APK 里，不需要 Service Worker 缓存，
        反而会让升级后仍显示旧界面 —— 直接注销掉 */
  try {
    var sw = navigator.serviceWorker;
    if (sw) {
      if (sw.getRegistrations) {
        sw.getRegistrations().then(function (list) {
          for (var i = 0; i < list.length; i++) {
            try { list[i].unregister(); } catch (e) {}
          }
        }).catch(function () {});
      }
      try {
        sw.register = function () { return Promise.reject(new Error('service worker disabled')); };
      } catch (e) {}
    }
  } catch (e) {}
})();

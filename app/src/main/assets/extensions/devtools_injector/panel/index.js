// Panel state, Eruda i18n, and custom Network tool registration.
function plainTextFromValue(v) {
  if (v == null) return null;
  if (typeof v === 'string') return v;
  try {
    if (v instanceof ArrayBuffer) {
      return new TextDecoder('utf-8', { fatal: false }).decode(new Uint8Array(v));
    }
    if (ArrayBuffer.isView && ArrayBuffer.isView(v)) {
      return new TextDecoder('utf-8', { fatal: false }).decode(new Uint8Array(v.buffer, v.byteOffset, v.byteLength));
    }
  } catch (e) {}
  try {
    if (v && typeof v.byteLength === 'number') {
      return new TextDecoder('utf-8', { fatal: false }).decode(new Uint8Array(v.buffer || v, v.byteOffset || 0, v.byteLength));
    }
  } catch (e2) {}
  return null;
}

function plainPrintableRatio(s) {
  if (!s) return 0;
  var ok = 0;
  var n = Math.min(s.length, 2048);
  for (var i = 0; i < n; i++) {
    var c = s.charCodeAt(i);
    if (c === 9 || c === 10 || c === 13 || c >= 32) ok++;
  }
  return n ? ok / n : 0;
}

function plainMeaningful(s, source) {
  if (!s || s.length < 6) return false;
  if (plainPrintableRatio(s) < 0.75) return false;
  if (source === 'crypto.encrypt') return true;
  return /[\{\[]|prompt|message|messages|content|conversation|query|variables|graphql|model|token|auth|session|chat/i.test(s);
}

function capturePlainValue(source, value, meta) {
  if (!netPlainProbeEnabled) return;
  var text = plainTextFromValue(value);
  if (!plainMeaningful(text, source)) return;
  recordPlainCandidate({
    source: source,
    plainText: text.slice(0, 65536),
    plainSize: text.length,
    meta: meta || {},
    ts: Date.now(),
  });
}

function injectPlainProbeViaExportFunction() {
  if (typeof exportFunction === 'undefined' || typeof window.wrappedJSObject === 'undefined') {
    runInPage(PLAIN_PROBE_JS);
    return;
  }
  var pw = window.wrappedJSObject;
  if (pw.__bhPlainProbeInstalled) return;
  pw.__bhPlainProbeInstalled = true;

  try {
    var jsonObj = pw.JSON;
    var _stringify = jsonObj && jsonObj.stringify;
    if (_stringify) {
      exportFunction(function () {
        var out = _stringify.apply(jsonObj, arguments);
        capturePlainValue('JSON.stringify', out, {});
        return out;
      }, jsonObj, { defineAs: 'stringify' });
    }
  } catch (e) {}

  try {
    var encProto = pw.TextEncoder && pw.TextEncoder.prototype;
    var _encode = encProto && encProto.encode;
    if (_encode) {
      exportFunction(function (input) {
        capturePlainValue('TextEncoder.encode', input, {});
        return _encode.apply(this, arguments);
      }, encProto, { defineAs: 'encode' });
    }
  } catch (e2) {}

  try {
    var subtle = pw.crypto && pw.crypto.subtle;
    var _encrypt = subtle && subtle.encrypt;
    if (_encrypt) {
      exportFunction(function (alg, key, data) {
        var name = '';
        try { name = (alg && alg.name) || String(alg || ''); } catch (e) {}
        capturePlainValue('crypto.encrypt', data, { algorithm: name });
        return _encrypt.apply(subtle, arguments);
      }, subtle, { defineAs: 'encrypt' });
    }
  } catch (e3) {}

  try {
    var wsProto = pw.WebSocket && pw.WebSocket.prototype;
    var _wsSend = wsProto && wsProto.send;
    if (_wsSend) {
      exportFunction(function (data) {
        capturePlainValue('WebSocket.send', data, {});
        return _wsSend.apply(this, arguments);
      }, wsProto, { defineAs: 'send' });
    }
  } catch (e4) {}
}
// ── /网络拦截层 ──────────────────────────────────────────────────────────────

// ── Eruda 汉化 ──────────────────────────────────────────────────────────────
var I18N_MAP = [
  // 顶部 Tab 名（DOM 里是小写，靠 CSS text-transform:capitalize 显示成首字母大写，
  // 所以这里必须用小写键才能匹配到文本节点）
  ['console', '控制台'], ['elements', '元素'], ['network', '网络'],
  ['resources', '存储'], ['sources', '源码'], ['info', '信息'],
  ['snippets', '代码片段'], ['settings', '设置'],
  // Console 面板
  ['Clear', '清空'], ['Filter', '过滤'], ['Preserve Log', '保留日志'],
  ['Show Timestamp', '显示时间戳'], ['Log', '日志'], ['Warn', '警告'],
  ['Error', '错误'], ['Info', '信息'], ['Debug', '调试'],
  ['Verbose', '详细'], ['Output', '输出'], ['JS', 'JS'],
  ['All', '全部'], ['Console', '控制台'],
  // Elements 面板
  ['Computed', '计算值'], ['Event Listeners', '事件监听器'],
  ['Styles', '样式'], ['Dom Tree', 'DOM 树'], ['Attributes', '属性'],
  // Resources 面板
  ['Local Storage', '本地存储'], ['Session Storage', '会话存储'],
  ['IndexedDB', 'IndexedDB'], ['Cache Storage', '缓存存储'],
  ['ServiceWorker', 'Service Worker'], ['Cookie', 'Cookie'],
  ['Scripts', '脚本'], ['Stylesheets', '样式表'], ['Images', '图片'],
  // Info 面板
  ['Location', '页面地址'], ['System', '系统信息'], ['About', '关于'],
  ['Backend', '后端'], ['Screen', '屏幕'],
  ['Browser', '浏览器'], ['Engine', '引擎'], ['OS', '操作系统'],
  ['Device', '设备'], ['CPU', '处理器'], ['Memory', '内存'],
  ['Language', '语言'], ['Languages', '语言列表'], ['Online', '在线'],
  ['Offline', '离线'], ['Platform', '平台'], ['Vendor', '厂商'],
  ['Cookie Enabled', 'Cookie 已启用'], ['Cookies Enabled', 'Cookie 已启用'],
  ['Hardware Concurrency', '硬件线程数'], ['Max Touch Points', '最大触点数'],
  ['Resolution', '分辨率'], ['Viewport', '视口'], ['Pixel Ratio', '像素比'],
  ['Color Depth', '色深'], ['Orientation', '方向'], ['Referrer', '来源页面'],
  ['Title', '标题'], ['Charset', '字符集'], ['Compat Mode', '兼容模式'],
  ['History Length', '历史长度'], ['Protocol', '协议'], ['Host', '主机'],
  ['Hostname', '主机名'], ['Port', '端口'], ['Pathname', '路径'],
  // Settings 面板
  ['Theme', '主题'], ['Transparency', '透明度'], ['Display Size', '显示大小'],
  ['Dark', '深色'], ['Light', '浅色'], ['Apply', '应用'],
  ['Close', '关闭'], ['Default', '默认'], ['Settings', '设置'],
  ['Log Level', '日志级别'], ['Max Log Number', '最大日志数'],
  ['Overflow', '溢出'], ['Wrap Long Lines', '长行换行'],
  // 通用按钮/标签
  ['Refresh', '刷新'], ['Copy', '复制'], ['Delete', '删除'],
  ['Expand', '展开'], ['Collapse', '折叠'], ['Search', '搜索'],
  ['Clear All', '全部清空'], ['Select All', '全选'],
  ['Cancel', '取消'], ['Confirm', '确认'], ['Save', '保存'],
  ['Reset', '重置'], ['Enable', '启用'], ['Disable', '禁用'],
  ['Open', '打开'], ['Add', '添加'], ['Edit', '编辑'],
  // Sources 面板（部分标签）
  ['Beautify', '格式化'], ['Word Wrap', '自动换行'],
  // ── Settings 面板（eruda 真实英文标签，逐字匹配）──
  ['Asynchronous Rendering', '异步渲染'],
  ['Enable JavaScript Execution', '启用 JavaScript 执行'],
  ['Catch Global Errors', '捕获全局错误'],
  ['Override Console', '接管 Console'],
  ['Auto Display If Error Occurs', '出错时自动显示'],
  ['Display Extra Information', '显示额外信息'],
  ['Display Unenumerable Properties', '显示不可枚举属性'],
  ['Access Getter Value', '读取 Getter 值'],
  ['Lazy Evaluation', '延迟求值'],
  ['Catch Event Listeners', '捕获事件监听器'],
  ['Auto Refresh Elements', '自动刷新元素'],
  ['Hide Eruda Setting', '隐藏 Eruda 设置'],
  ['Show Line Numbers', '显示行号'],
  ['Remember Entry Button Position', '记住入口按钮位置'],
  ['Restore defaults and reload', '恢复默认并重新加载'],
  ['System preference', '跟随系统'],
  ['infinite', '不限'],
  // ── Elements 盒模型分类名（DOM 里是小写，靠 capitalize 显示）──
  ['margin', '外边距'], ['border', '边框'], ['padding', '内边距'],
  ['content', '内容'], ['element.style', 'element.style'],
	    // ── Info / Snippets 分区与条目名 ──
	    ['User Agent', '用户代理'], ['Device', '设备'],
	    ['URL', '网址'], ['Url', '网址'], ['Origin', '源'], ['Domain', '域名'],
	    ['Hash', '片段'], ['Query', '查询参数'], ['Document', '文档'],
	    ['App Name', '应用名称'], ['App Version', '应用版本'],
	    ['Browser Version', '浏览器版本'], ['Engine Version', '引擎版本'],
	    ['Product', '产品'], ['Product Sub', '产品子版本'], ['Vendor Sub', '厂商子版本'],
	    ['Build ID', '构建 ID'], ['Do Not Track', '禁止跟踪'],
	    ['Device Pixel Ratio', '设备像素比'], ['Screen Size', '屏幕尺寸'],
	    ['Window Size', '窗口尺寸'], ['Touch Support', '触控支持'],
	    ['Timezone', '时区'], ['Timezone Offset', '时区偏移'],
	    ['Connection', '网络连接'], ['Effective Type', '有效网络类型'],
	    ['Downlink', '下行速度'], ['RTT', '往返延迟'], ['Save Data', '省流量模式'],
	    ['Java Enabled', 'Java 已启用'], ['PDF Viewer Enabled', 'PDF 查看器已启用'],
	    ['Storage', '存储'], ['Quota', '配额'], ['Usage', '用量'],
	    ['Border All', '显示所有边框'], ['Refresh Page', '刷新页面'],
  ['Search Text', '搜索文本'], ['Edit Page', '编辑页面'],
  ['Fit Screen', '适应屏幕'],
];

var I18N_DICT = null;
// 数据展示区：这些容器里的文本是用户数据（console 输出、DOM 内容、存储的值、
// JSON/对象值等），绝不能翻译，否则数据会被破坏。
// 注意：只匹配"真正承载数据值"的容器——
//   luna-console        控制台输出
//   luna-data-grid-data 表格的数据行（表头 luna-data-grid-header-* 不在内，分类名可翻译）
//   luna-dom-viewer     DOM 树内容
//   luna-object-viewer  对象属性值
//   luna-json           JSON 数据
//   bh-net              自定义网络面板（URL/请求体等都是数据）
// 不再整块屏蔽 eruda-resources/eruda-sources/eruda-logs 面板，
// 这样面板里的分类名/表头/区块标题可以被翻译。
var DATA_REGION_RE = /luna-console|luna-data-grid-data|luna-dom-viewer|luna-object-viewer|luna-json|bh-net/;
function inDataRegion(node) {
  var el = node.parentNode;
  while (el && el.nodeType === 1) {
    var cls = el.className;
    if (typeof cls === 'string' && DATA_REGION_RE.test(cls)) {
      return true;
    }
    el = el.parentNode;
  }
  return false;
}

function replaceTextNodes(root) {
  if (!root) return;
  if (!I18N_DICT) {
    I18N_DICT = {};
    for (var j = 0; j < I18N_MAP.length; j++) I18N_DICT[I18N_MAP[j][0]] = I18N_MAP[j][1];
  }
  function tr(node) {
    var v = node.nodeValue;
    if (!v) return;
    var t = v.trim();
    if (!t) return;
    var zh = I18N_DICT[t];
    if (!zh || zh === t) return;
    if (inDataRegion(node)) return; // 数据区不翻译
    node.nodeValue = v.replace(t, zh);
  }
  if (root.nodeType === 3) { tr(root); return; }
  if (root.nodeType !== 1 && root.nodeType !== 11) return;
  var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null, false);
  var node;
  while ((node = walker.nextNode())) tr(node);
}

function _i18nCore() {
  // page-world 模式：shadow root 在页面 window 里，content script 无法直接访问
  // 用 runInPage 注入一段脚本在页面 window 里操作
  if (erudaMode === 'page') {
    var mapJson = JSON.stringify(I18N_MAP);
    runInPage([
      '(function(){',
      '  var host=document.getElementById("eruda");',
      '  if(!host||!host.shadowRoot)return;',
      '  var root=host.shadowRoot;',
      '  var MAP=' + mapJson + ';',
      '  var DICT={};for(var k=0;k<MAP.length;k++){DICT[MAP[k][0]]=MAP[k][1];}',
      '  var DATA_RE=/luna-console|luna-data-grid-data|luna-dom-viewer|luna-object-viewer|luna-json|bh-net/;',
      '  function inData(node){',
      '    var el=node.parentNode;',
      '    while(el&&el.nodeType===1){',
      '      var c=el.className;',
      '      if(typeof c==="string"&&DATA_RE.test(c))return true;',
      '      el=el.parentNode;',
      '    }',
      '    return false;',
      '  }',
      '  function tr(node){',
      '    var v=node.nodeValue;if(!v)return;',
      '    var t=v.trim();if(!t)return;',
      '    var zh=DICT[t];',
      '    if(!zh||zh===t)return;',
      '    if(inData(node))return;',  // 数据区不翻译，保护用户数据
      '    node.nodeValue=v.replace(t,zh);',  // 保留原前后空白
      '  }',
      '  function walk(el){',
      '    if(!el)return;',
      '    if(el.nodeType===3){tr(el);return;}',
      '    if(el.nodeType!==1&&el.nodeType!==11)return;',
      '    var tw=document.createTreeWalker(el,NodeFilter.SHOW_TEXT,null,false);',
      '    var n;while((n=tw.nextNode())){tr(n);}',
      '  }',
      '  walk(root);',
      '  var obs=new MutationObserver(function(muts){',
      '    muts.forEach(function(m){',
      '      if(m.type==="characterData"){tr(m.target);return;}',
      '      m.addedNodes.forEach(function(node){walk(node);});',
      '    });',
      '  });',
      '  obs.observe(root,{childList:true,subtree:true,characterData:true});',
      // 懒加载的 tab 面板在切换时才渲染；定时重扫几次兜底
      '  var c=0;var iv=setInterval(function(){walk(root);if(++c>=10)clearInterval(iv);},500);',
      '})();',
    ].join('\n'));
    return;
  }
  // isolated-world 模式：可以直接访问 DOM（包括 shadow root）
  var host = document.getElementById('eruda');
  var root = host && (host.shadowRoot || host);
  if (!root) return;
  replaceTextNodes(root);
  var obs = new MutationObserver(function (muts) {
    muts.forEach(function (m) {
      if (m.type === 'characterData') { replaceTextNodes(m.target); return; }
      m.addedNodes.forEach(function (node) { replaceTextNodes(node); });
    });
  });
  obs.observe(root, { childList: true, subtree: true, characterData: true });
  var c = 0;
  var iv = setInterval(function () { replaceTextNodes(root); if (++c >= 10) clearInterval(iv); }, 500);
}
// ── /Eruda 汉化 ─────────────────────────────────────────────────────────────

// ── 抓包面板 ─────────────────────────────────────────────────────────────────
var NET_STYLE = [
  '*{box-sizing:border-box;margin:0;padding:0;}',
  // 面板根：固定浅色主题，不依赖 CSS 变量（shadow root 内变量继承不可靠）
  '#bh-net{display:flex;flex-direction:column;height:100%;font-size:15px;',
  '  font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;',
  '  background:#fff;color:#111;pointer-events:auto;touch-action:auto;}',
  // 顶部工具栏
  '#bh-bar{position:relative;z-index:2;display:flex;align-items:center;gap:8px;padding:8px 10px;',
  '  border-bottom:1px solid #d0d7de;flex-wrap:wrap;background:#f6f8fa;}',
  '#bh-bar button{font-size:14px;padding:8px 12px;border-radius:6px;min-height:40px;',
  '  border:1px solid #d0d7de;background:#fff;color:#111;cursor:pointer;white-space:nowrap;}',
  '#bh-bar button:active{background:#e8eaed;}',
  '#bh-filter-wrap{position:relative;display:inline-flex;}',
  '#bh-filter-menu{display:none;position:absolute;left:0;top:calc(100% + 5px);min-width:128px;',
  '  padding:4px;background:#fff;border:1px solid #d0d7de;border-radius:6px;',
  '  box-shadow:0 8px 24px rgba(0,0,0,.18);z-index:2147483642;}',
  '#bh-filter-menu.open{display:flex;flex-direction:column;gap:2px;}',
  '#bh-filter-menu button{width:100%;text-align:left;border:none;border-radius:4px;',
  '  background:#fff;min-height:36px;padding:7px 10px;}',
  '#bh-filter-menu button:active{background:#e8eaed;}',
  '#bh-filter{flex:1;min-width:100px;font-size:14px;padding:8px 10px;border-radius:6px;min-height:40px;',
  '  border:1px solid #d0d7de;background:#fff;color:#111;}',
  // 请求列表
	    '#bh-list{flex:1 1 auto;min-height:0;overflow-y:auto;border-bottom:1px solid #d0d7de;}',
	    '#bh-empty{padding:20px;text-align:center;color:#888;font-size:14px;}',
	    '.bh-section-title{position:sticky;top:0;z-index:1;padding:6px 10px;border-bottom:1px solid #d0d7de;',
	    '  background:#f6f8fa;color:#374151;font-size:12px;font-weight:700;}',
	    '.bh-section-empty{padding:12px 10px;border-bottom:1px solid #eaecef;color:#888;font-size:12px;background:#fff;}',
	    '.bh-row{position:relative;display:flex;align-items:center;padding:10px 66px 10px 10px;min-height:58px;',
	    '  border-bottom:1px solid #eaecef;cursor:pointer;gap:8px;background:#fff;}',
	    '.bh-row:active,.bh-row.bh-sel{background:#dbeafe;}',
	    '.bh-row.bh-intercept{background:#fff7ed;}',
	    '.bh-row.bh-intercept.bh-sel,.bh-row.bh-intercept:active{background:#fed7aa;}',
  '.bh-method{font-weight:700;min-width:46px;font-size:13px;color:#111;}',
  '.bh-main{flex:1;min-width:0;display:flex;flex-direction:column;gap:3px;}',
  '.bh-url{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:14px;color:#111;}',
  '.bh-meta{font-size:11px;color:#6b7280;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}',
  '.bh-tag{font-size:11px;padding:2px 6px;border-radius:3px;background:#6366f1;color:#fff;}',
  // 状态颜色
  '.s2{color:#16a34a;}.s3{color:#2563eb;}.s4{color:#ea580c;}.s5{color:#dc2626;}.s0{color:#888;}.s-err{color:#dc2626;font-style:italic;}',
  '.bh-status-wrap{position:absolute;right:8px;top:7px;display:flex;flex-direction:column;align-items:flex-end;min-width:44px;}',
  '.bh-status{font-size:11px;font-weight:700;line-height:1.15;}',
  '.bh-status-desc{font-size:9px;color:#888;line-height:1.1;max-width:52px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}',
  // 详情区：展开后占面板大部分高度，让请求/响应体有足够阅读空间
  '#bh-detail{flex:1 1 auto;min-height:0;overflow:hidden;display:flex;flex-direction:column;background:#fff;}',
  '#bh-detail-head{display:flex;align-items:stretch;border-bottom:1px solid #d0d7de;background:#f6f8fa;flex:0 0 auto;}',
  '#bh-detail-tabs{display:flex;overflow-x:auto;background:#f6f8fa;flex:1 1 auto;min-width:0;}',
  '.bh-dtab{padding:10px 14px;font-size:14px;cursor:pointer;white-space:nowrap;color:#111;',
  '  min-height:42px;display:flex;align-items:center;border-bottom:2px solid transparent;}',
  '.bh-dtab.active{border-bottom-color:#2563eb;font-weight:700;}',
  '#bh-detail-close{flex:0 0 44px;min-width:44px;border:none;border-left:1px solid #d0d7de;',
  '  background:#f6f8fa;color:#555;font-size:24px;line-height:1;cursor:pointer;}',
  '#bh-detail-close:active{background:#e8eaed;color:#111;}',
  // textarea 代替 div：原生支持长按选中复制、单点光标编辑
	    '#bh-detail-body{flex:1 1 0;min-height:0;overflow:auto;padding:10px;font-size:13px;color:#111;',
	    '  white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word;font-family:monospace;background:#fff;',
  '  border:none;outline:none;resize:none;width:100%;line-height:1.5;}',
  '#bh-detail-acts{display:flex;gap:8px;padding:8px 10px;flex-wrap:wrap;flex:0 0 auto;',
  '  border-top:1px solid #d0d7de;background:#f6f8fa;}',
  '#bh-detail-acts button{font-size:14px;padding:8px 12px;border-radius:6px;min-height:40px;',
  '  border:1px solid #d0d7de;background:#fff;color:#111;cursor:pointer;}',
	    '#bh-detail-acts button:active{background:#e8eaed;}',
	    // 编辑时不再移动详情区，避免 Android 软键盘/滚动联动造成上下弹跳。
	    '#bh-rules-view,#bh-ext-view{display:none;position:fixed;inset:0;z-index:2147483646;background:#fff;color:#111;',
	    '  flex-direction:column;pointer-events:auto;touch-action:auto;}',
	    '#bh-rules-view.open,#bh-ext-view.open{display:flex;}',
	    '#bh-ext-head{display:flex;align-items:center;gap:8px;padding:10px 12px;border-bottom:1px solid #d0d7de;background:#f6f8fa;}',
	    '#bh-ext-title{flex:1;font-size:15px;font-weight:700;}',
	    '#bh-ext-close{flex:0 0 44px;min-width:44px;border:1px solid #d0d7de;border-radius:6px;background:#fff;color:#555;',
	    '  font-size:24px;line-height:1;min-height:40px;}',
	    '#bh-ext-body{flex:1;display:flex;align-items:center;justify-content:center;color:#888;font-size:14px;}',
	    '#bh-rules-head{display:flex;align-items:center;gap:8px;padding:10px 12px;border-bottom:1px solid #d0d7de;background:#f6f8fa;}',
	    '#bh-rules-title{flex:1;font-size:15px;font-weight:700;}',
	    '#bh-rules-close{flex:0 0 44px;min-width:44px;border:1px solid #d0d7de;border-radius:6px;background:#fff;color:#555;',
	    '  font-size:24px;line-height:1;min-height:40px;}',
	    '#bh-rules-list{flex:1;min-height:0;overflow-y:auto;}',
	    '.bh-rule-row{display:flex;align-items:center;gap:8px;padding:10px 12px;min-height:62px;border-bottom:1px solid #eaecef;background:#fff;}',
	    '.bh-rule-row:active{background:#dbeafe;}',
	    '.bh-rule-action{flex:0 0 42px;text-align:center;border-radius:4px;padding:3px 0;font-size:12px;font-weight:700;color:#fff;}',
	    '.bh-rule-action.pass{background:#16a34a;}.bh-rule-action.intercept{background:#dc2626;}',
	    '.bh-rule-main{flex:1;min-width:0;display:flex;flex-direction:column;gap:3px;}',
	    '.bh-rule-url{font-size:13px;color:#111;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}',
	    '.bh-rule-meta{font-size:11px;color:#6b7280;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}',
	    '.bh-rule-empty{padding:24px;text-align:center;color:#888;font-size:13px;}',
	    // 模态弹窗（挂进 #bh-net / shadow root 内，z-index 要高于 eruda 面板内部元素）
  '#bh-modal{position:fixed;inset:0;z-index:2147483647;display:flex;align-items:center;',
  '  justify-content:center;background:rgba(0,0,0,.5);}',
  '#bh-modal-box{background:#fff;border-radius:8px;color:#111;',
  '  width:90vw;max-height:85vh;display:flex;flex-direction:column;overflow:hidden;}',
  '#bh-modal-title{padding:10px 12px;font-weight:700;font-size:14px;',
  '  border-bottom:1px solid #d0d7de;}',
  '#bh-modal-body{flex:1;overflow-y:auto;padding:10px 12px;display:flex;flex-direction:column;gap:8px;}',
  '#bh-modal-body label{font-size:12px;font-weight:600;}',
  '#bh-modal-body input,#bh-modal-body textarea,#bh-modal-body select{',
  '  width:100%;font-size:12px;padding:6px;border-radius:4px;',
  '  border:1px solid #d0d7de;background:#f6f8fa;color:#111;font-family:monospace;}',
  '#bh-modal-body textarea{resize:vertical;min-height:80px;}',
  '#bh-modal-acts{display:flex;gap:8px;padding:8px 12px;',
  '  border-top:1px solid #d0d7de;justify-content:flex-end;}',
  '#bh-modal-acts button{font-size:13px;padding:6px 14px;border-radius:4px;',
  '  border:1px solid #d0d7de;cursor:pointer;}',
  '#bh-btn-ok{background:#2563eb;color:#fff;border-color:#2563eb;}',
  '#bh-btn-cancel{background:#f6f8fa;color:#111;}',
  // 空状态
  '#bh-empty{padding:24px;text-align:center;color:#888;font-size:13px;}',
  // 断点高亮
  '.bh-row.bh-bp{background:#fef3c7;}',
  // 额外功能下拉菜单
  '#bh-extra-wrap{position:relative;display:inline-flex;}',
  '#bh-extra-menu{display:none;position:absolute;left:0;top:calc(100% + 5px);min-width:140px;' +
  '  padding:4px;background:#fff;border:1px solid #d0d7de;border-radius:6px;' +
  '  box-shadow:0 8px 24px rgba(0,0,0,.18);z-index:2147483642;}',
  '#bh-extra-menu.open{display:flex;flex-direction:column;gap:2px;}',
  '#bh-extra-menu button{width:100%;text-align:left;border:none;border-radius:4px;' +
  '  background:#fff;min-height:40px;padding:7px 10px;font-size:14px;cursor:pointer;}',
  '#bh-extra-menu button:active{background:#e8eaed;}',
  // 详情搜索栏
  '#bh-detail-search{display:flex;align-items:center;gap:6px;padding:4px 10px;flex:0 0 auto;' +
  '  border-bottom:1px solid #d0d7de;background:#f6f8fa;}',
  '#bh-dsearch{flex:1;font-size:13px;padding:5px 8px;border-radius:4px;min-height:32px;' +
  '  border:1px solid #d0d7de;background:#fff;color:#111;}',
  '#bh-dsearch-count{font-size:12px;color:#6b7280;white-space:nowrap;min-width:32px;text-align:right;}',
  '#bh-dsearch-nav button{font-size:16px;padding:2px 8px;border-radius:4px;min-height:32px;' +
  '  border:1px solid #d0d7de;background:#fff;color:#111;cursor:pointer;}',
  '#bh-dsearch-nav button:active{background:#e8eaed;}',
].join('');

var netPanel = null;   // 根 div
var netListEl = null;
var netDetailEl = null;
var netDetailBody = null;
var netDetailActs = null;
var netFilterEl = null;
var netEnableBtn = null;
	  var netSelReq = null;  // 当前选中的请求条目
	  var netDetailTab = 0;  // 0=请求头 1=请求体 2=响应头 3=响应体 4=明文
	  var netModal = null;   // 当前弹窗 element
	  var netEditing = false; // 详情 textarea 是否正在被编辑（编辑时不覆盖内容）
	  var netDetailDirty = false;
	  var netHideTunnelNoise = false;
var netPayloadOnly = false;
var netPlainProbeEnabled = false;
// 主开关（拦截按钮）：决定拦截功能是否生效
var netInterceptMaster = false;
// 作用域（长按配置）：主开关开启时分别决定是否拦请求/响应/噪音包
var netScopeReq = true;
var netScopeResp = false;
var netScopeNoise = false;
// 派生的生效标志（= 主开关 && 对应作用域），拦截器实际读取这三个
var netGlobalInterceptEnabled = false;
var netGlobalRespInterceptEnabled = false;
var netGlobalInterceptNoise = false;
// 字符替换作用域：'req' / 'resp' / 'both'
var netReplaceScope = 'both';
// 响应拦截过滤遥测ACK：true = 过滤掉 204/空体等压制响应，不拦截它们
var netScopeFilterSuppressResp = true;
// 控制台绕过：为 true 时拦截器放行所有请求（用于 eruda 控制台执行代码）
var netConsoleBypass = false;
var netFilterMenuOpen = false;
var netExtraMenuOpen = false;
var netReplaceRules = [];
var netReplaceEnabled = false;
var netDetailSearchText = '';
var netDetailSearchMatches = [];
var netDetailSearchIdx = 0;

// ── 控制台绕过拦截器：在 eruda console 执行代码时临时绕过拦截 ──
// 避免用户在 eruda 控制台执行 fetch/XHR 命令被拦截器拦住。
function hookErudaConsoleBypass(erudaObj) {
  if (!erudaObj || !erudaObj.get) return;
  try {
    var c = erudaObj.get('console');
    if (!c || typeof c.evaluate !== 'function') return;
    var _origEval = c.evaluate.bind(c);
    c.evaluate = function (code) {
      netConsoleBypass = true;
      // 同时对 page world 拦截器设旁路标志（以防 INTERCEPT_JS 路径也被触发）
      if (typeof window.wrappedJSObject !== 'undefined') {
        try { window.wrappedJSObject.__bhNoIntercept = true; } catch (e) {}
      }
      try { return _origEval(code); }
      finally {
        netConsoleBypass = false;
        if (typeof window.wrappedJSObject !== 'undefined') {
          try { window.wrappedJSObject.__bhNoIntercept = false; } catch (e) {}
        }
      }
    };
  } catch (e) {}
}

// ── 注册 eruda 自定义 Tool（isolated world）──
// eruda.add 只调用 tool.init($el)，不调用 render()。基类 init 是 this._$el=e。
function registerNetTool(erudaObj) {
  if (!erudaObj || !erudaObj.add) return;
  var tool = {
    name: '网络',
    _$el: null,
    init: function ($el) {
      this._$el = $el;
      var node = ($el && $el[0]) || $el;
      if (!netPanel) buildNetPanel();
      if (node && node.appendChild) node.appendChild(netPanel);
    },
    show: function () {
      if (this._$el && this._$el.show) this._$el.show();
      netPanelVisible = true;
      renderNetList();
      renderDetail();
      return this;
    },
    hide: function () {
      if (this._$el && this._$el.hide) this._$el.hide();
      netPanelVisible = false;
      return this;
    },
    destroy: function () { netPanelVisible = false; },
  };
  try { erudaObj.add(tool); } catch (e) {}
}

// ── 在 installI18n 同一时机注册 Tool & 注入拦截器 ──
	  function installI18n() {
	    _i18nCore();
	    installPointerGuard();
	    // Phase 1+：拦截由原生代理处理，不再注入页内逻辑
	    loadNetConfig(function () {
	      loadInterceptRules();
	      loadReplaceRules();
	      // 不再调用 injectInterceptor() 等页内拦截器函数
	      // 所有网络层拦截由 ProxyProbe 原生处理
	      injectPlainProbe();  // 保留：明文探针仍用页内 hook
	      // 同步持久化状态到面板 UI（按钮颜色/文字）
	      updateInterceptBtn();
	      updateFilterButtons();
	      updateReplaceBtn();
	      // 同步配置到原生代理
	      proxySendConfig();
	    });
	  // page-world: eruda 在 window.eruda; isolated: self.eruda
	  var erudaObj = (erudaMode === 'page') ? null : (self.eruda || null);
	  if (erudaMode === 'page') {
    // page world 的 eruda 对象在页面 window 里，content script 无法直接调用 eruda.add()
    // 改为注入一段脚本在页面 window 里注册 Tool
    injectNetToolPageWorld();
	  } else {
    registerNetTool(erudaObj);
    hookErudaConsoleBypass(erudaObj);
  }
}

// page-world 模式下注册 eruda 自定义 Tool。
//
// 关键：eruda 的 DevTools.add(tool) 只会调用 tool.init($container, devtools)，
// 不会调用 render()；基类 init 是 this._$el=e，show/hide 走 this._$el.show()/.hide()。
// 之前的实现用 render() 返回 DOM，eruda 从不调用，所以面板永远空白。
//
// 同时存在跨世界问题：抓包面板的 DOM 与全部交互逻辑都在 content-script 世界，
// 而 eruda 在 page 世界。但两个世界共享同一份 DOM —— 所以做法是：
//   1. content world 用 buildNetPanel() 构建 #bh-net 元素（事件已绑定在 content world）
//   2. page world 的 tool.init($el) 拿到 eruda 容器后，用 DOM 查询找到 #bh-net 把它 append 进去
//   3. show()/hide() 通过 postMessage 通知 content world 渲染（沿用 __bhNet 通道）
function injectNetToolPageWorld() {
  if (!netPanel) buildNetPanel();
  // 先把面板挂在 body 上（隐藏），等 eruda tool.init 时再移进容器
  netPanel.style.display = 'none';
  document.body.appendChild(netPanel);

  // content world 监听来自 page world 的 show/hide 消息（已在上面的 message 监听里处理 panelShow/panelHide）

  runInPage([
    '(function(){',
    '  if(!window.eruda||!window.eruda.add)return;',
    '  if(window.__bhNetToolAdded)return;',
    '  window.__bhNetToolAdded=true;',
    '  window.eruda.add(function(devtools){',
    '    return {',
    '      name:"网络",',  // tab label
    '      init:function($el){',
    '        this._$el=$el;',
    '        var node=($el&&$el[0])||($el&&$el.get&&$el.get(0))||$el;',
    '        var panel=document.getElementById("bh-net");',
    '        if(panel&&node&&node.appendChild){node.appendChild(panel);panel.style.display="flex";}',
    '      },',
    '      show:function(){',
    '        if(this._$el&&this._$el.show)this._$el.show();',
    '        var panel=document.getElementById("bh-net");if(panel)panel.style.display="flex";',
    '        try{window.postMessage({__bhNet:true,type:"panelShow"},"*");}catch(e){}',
    '        return this;',
    '      },',
    '      hide:function(){',
    '        if(this._$el&&this._$el.hide)this._$el.hide();',
    '        try{window.postMessage({__bhNet:true,type:"panelHide"},"*");}catch(e){}',
    '        return this;',
    '      },',
    '      destroy:function(){},',
    '    };',
    '  });',
    '})();',
  ].join('\n'));
}
// ── /抓包面板 ────────────────────────────────────────────────────────────────

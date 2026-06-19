// 拓展面板：与「网络」同级的独立 Eruda Tool（不再是网络面板的子页）。
// 结构镜像 panel/index.js 的网络 Tool：content world 构建 #bh-ext，
// page/isolated 两世界各自把它 append 进对应的 eruda 容器。
var extPanel = null;

var EXT_STYLE = [
  '#bh-ext{display:flex;flex-direction:column;height:100%;font-size:15px;',
  '  font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;',
  '  background:#fff;color:#111;pointer-events:auto;touch-action:auto;}',
  '#bh-ext-head{display:flex;align-items:center;gap:8px;padding:10px 12px;',
  '  border-bottom:1px solid #d0d7de;background:#f6f8fa;}',
  '#bh-ext-title{flex:1;font-size:15px;font-weight:700;}',
  '#bh-ext-body{flex:1;overflow:auto;padding:12px;display:grid;',
  '  grid-template-columns:repeat(2,1fr);gap:12px;align-content:start;}',
  '.bh-ext-card{display:flex;flex-direction:column;gap:8px;padding:14px;',
  '  border:1px solid #d0d7de;border-radius:16px;background:#fff;',
  '  box-shadow:0 1px 3px rgba(0,0,0,.06);min-height:120px;}',
  '.bh-ext-card-info{flex:1;min-width:0;}',
  '.bh-ext-card-name{font-size:15px;font-weight:600;color:#111;}',
  '.bh-ext-card-desc{font-size:12px;color:#888;margin-top:4px;line-height:1.4;}',
  '.bh-ext-toggle{align-self:stretch;padding:8px 0;border:none;border-radius:10px;',
  '  font-size:14px;font-weight:600;color:#fff;}',
  '.bh-ext-toggle.on{background:#16a34a;}',
  '.bh-ext-toggle.off{background:#6b7280;}',
].join('');

// 单个拓展方块卡：名称 + 描述 + 底部「启用/禁用」开关按钮。
// 按钮态随 isPluginEnabled 切换，点击 togglePlugin 后刷新本卡。
function syncExtToggleBtn(btn, id) {
  var on = (typeof isPluginEnabled === 'function') && isPluginEnabled(id);
  btn.className = 'bh-ext-toggle ' + (on ? 'on' : 'off');
  btn.textContent = on ? '● 已启用' : '○ 已禁用';
}

function buildExtCard(p) {
  var card = document.createElement('div');
  card.className = 'bh-ext-card';
  var info = document.createElement('div');
  info.className = 'bh-ext-card-info';
  var name = document.createElement('div');
  name.className = 'bh-ext-card-name';
  name.textContent = p.name || '';
  info.appendChild(name);
  if (p.desc) {
    var desc = document.createElement('div');
    desc.className = 'bh-ext-card-desc';
    desc.textContent = p.desc;
    info.appendChild(desc);
  }
  card.appendChild(info);
  var btn = document.createElement('button');
  btn.className = 'bh-ext-toggle off';
  syncExtToggleBtn(btn, p.id);
  btn.addEventListener('click', function () {
    if (typeof togglePlugin === 'function') togglePlugin(p.id);
    syncExtToggleBtn(btn, p.id);
  });
  card.appendChild(btn);
  return card;
}

// 重新同步面板里所有卡片的开关态（恢复/外部变更后调用）。
function refreshExtCards() {
  if (!extPanel) return;
  var btns = extPanel.querySelectorAll('.bh-ext-toggle');
  var list = (typeof BH_PLUGINS !== 'undefined' && BH_PLUGINS) || [];
  for (var i = 0; i < btns.length && i < list.length; i++) {
    syncExtToggleBtn(btns[i], list[i].id);
  }
}

function buildExtPanel() {
  if (extPanel) return extPanel;
  var wrap = document.createElement('div');
  wrap.id = 'bh-ext';
  ['pointerdown', 'pointerup', 'touchstart', 'touchend', 'mousedown', 'mouseup', 'click', 'dblclick', 'contextmenu'].forEach(function (type) {
    wrap.addEventListener(type, function (e) { e.stopPropagation(); }, false);
  });
  var style = document.createElement('style');
  style.textContent = EXT_STYLE;
  wrap.appendChild(style);
  var head = document.createElement('div');
  head.id = 'bh-ext-head';
  head.innerHTML = '<div id="bh-ext-title">拓展</div>';
  wrap.appendChild(head);
  var body = document.createElement('div');
  body.id = 'bh-ext-body';
  var plugins = (typeof BH_PLUGINS !== 'undefined' && BH_PLUGINS) || [];
  plugins.forEach(function (p) { body.appendChild(buildExtCard(p)); });
  wrap.appendChild(body);
  extPanel = wrap;
  return wrap;
}

// eruda 把 settings 之外的所有 tool 都插在「设置」之前，所以「拓展」默认排在设置左边、
// 紧挨网络。注册后把它的 nav tab DOM 挪到父容器末尾，即排到「设置」右边（最右）。
function moveExtTabLast() {
  var tries = 0;
  (function go() {
    try {
      var host = document.querySelector('.eruda-container');
      var sr = host && host.shadowRoot;
      var item = sr && sr.querySelector('.eruda-item[data-id="\u62d3\u5c55"]');
      if (item && item.parentNode) { item.parentNode.appendChild(item); return; }
    } catch (e) {}
    if (tries++ < 30) setTimeout(go, 100);
  }());
}

// isolated world：直接 eruda.add 一个名为「拓展」的 Tool。
function registerExtTool(erudaObj) {
  if (!erudaObj || !erudaObj.add) return;
  var tool = {
    name: '拓展',
    _$el: null,
    init: function ($el) {
      this._$el = $el;
      var node = ($el && $el[0]) || $el;
      if (!extPanel) buildExtPanel();
      if (node && node.appendChild) node.appendChild(extPanel);
    },
    show: function () { if (this._$el && this._$el.show) this._$el.show(); return this; },
    hide: function () { if (this._$el && this._$el.hide) this._$el.hide(); return this; },
    destroy: function () {},
  };
  try { erudaObj.add(tool); } catch (e) {}
  moveExtTabLast();
}

// page world：content script 无法直接调用页面里的 eruda.add，注入脚本在页面世界注册。
// #bh-ext 由 content world 构建（先挂 body），page world 的 tool.init 再把它移进容器。
function injectExtToolPageWorld() {
  if (!extPanel) buildExtPanel();
  extPanel.style.display = 'none';
  document.body.appendChild(extPanel);
  runInPage([
    '(function(){',
    '  if(!window.eruda||!window.eruda.add)return;',
    '  if(window.__bhExtToolAdded)return;',
    '  window.__bhExtToolAdded=true;',
    '  window.eruda.add(function(devtools){',
    '    return {',
    '      name:"拓展",',
    '      init:function($el){',
    '        this._$el=$el;',
    '        var node=($el&&$el[0])||($el&&$el.get&&$el.get(0))||$el;',
    '        var panel=document.getElementById("bh-ext");',
    '        if(panel&&node&&node.appendChild){node.appendChild(panel);panel.style.display="flex";}',
    '      },',
    '      show:function(){',
    '        if(this._$el&&this._$el.show)this._$el.show();',
    '        var panel=document.getElementById("bh-ext");if(panel)panel.style.display="flex";',
    '        return this;',
    '      },',
    '      hide:function(){if(this._$el&&this._$el.hide)this._$el.hide();return this;},',
    '      destroy:function(){},',
    '    };',
    '  });',
    // 把「拓展」tab 挪到设置右边（eruda 默认把它插在设置左边）。
    '  var tries=0;(function go(){try{',
    '    var host=document.querySelector(".eruda-container");',
    '    var sr=host&&host.shadowRoot;',
    '    var item=sr&&sr.querySelector(".eruda-item[data-id=\\"\u62d3\u5c55\\"]");',
    '    if(item&&item.parentNode){item.parentNode.appendChild(item);return;}',
    '  }catch(e){}if(tries++<30)setTimeout(go,100);})();',
    '})();',
  ].join('\n'));
}

// 在网络 Tool 注册的同一时机调用，按当前 eruda 世界注册「拓展」Tool。
function installExtTool() {
  if (erudaMode === 'page') {
    injectExtToolPageWorld();
  } else {
    registerExtTool(self.eruda || null);
  }
  // 面板就绪后恢复已启用的插件（幂等），并把卡片态刷成最新。
  if (typeof restoreEnabledPlugins === 'function') restoreEnabledPlugins();
  return BH_PLUGINS;
}

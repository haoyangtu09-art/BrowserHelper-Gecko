// Replace rule CRUD and persistence.
function openReplaceModal() {
  var listHtml = netReplaceRules.map(function (rule, i) {
    var fromShort = rule.from.length > 20 ? rule.from.slice(0, 20) + '…' : rule.from;
    var toShort = rule.to.length > 20 ? rule.to.slice(0, 20) + '…' : rule.to;
    return '<div style="display:flex;gap:6px;align-items:center;padding:6px 0;border-bottom:1px solid #eaecef;">' +
      '<span style="flex:1;font-size:12px;font-family:monospace;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">' +
        escHtml(fromShort) + ' → ' + escHtml(toShort) + '</span>' +
      '<button data-ri-toggle="' + i + '" style="font-size:11px;padding:2px 6px;border-radius:3px;border:1px solid #d0d7de;background:' + (rule.enabled ? '#dbeafe' : '#fff') + ';">' +
        (rule.enabled ? '开' : '关') + '</button>' +
      '<button data-ri-del="' + i + '" style="font-size:11px;padding:2px 6px;border-radius:3px;border:1px solid #fecaca;background:#fff5f5;color:#dc2626;">删</button>' +
    '</div>';
  }).join('') || '<div style="color:#888;font-size:12px;padding:8px 0;">暂无替换规则</div>';
  var scopeHtml = '<div style="display:flex;gap:6px;margin-bottom:8px;margin-top:2px;">' +
    ['req', 'both', 'resp'].map(function (s) {
      var labels = { req: '仅发出', both: '双向', resp: '仅接收' };
      var active = netReplaceScope === s;
      return '<button data-rscope="' + s + '" style="flex:1;font-size:12px;padding:5px 4px;border-radius:4px;border:1px solid ' +
        (active ? '#2563eb' : '#d0d7de') + ';background:' + (active ? '#dbeafe' : '#fff') + ';color:' + (active ? '#2563eb' : '#555') + ';font-weight:' + (active ? '700' : '400') + ';">' +
        labels[s] + '</button>';
    }).join('') + '</div>';
  openModal('字符替换',
    '<div style="font-size:11px;color:#6b7280;margin-bottom:2px;">替换方向</div>' +
    scopeHtml +
    listHtml +
    '<label style="margin-top:8px;">被替换字符串</label><input id="bh-repl-from" placeholder="要被替换的内容">' +
    '<label>替换为</label><input id="bh-repl-to" placeholder="替换进去的内容（空=删除）">',
    function (el) {
      var from = el.querySelector('#bh-repl-from').value;
      var to = el.querySelector('#bh-repl-to').value;
      if (from) {
        netReplaceRules.push({ id: Date.now().toString(36), from: from, to: to, enabled: true });
        saveReplaceRules();
        updateReplaceBtn();
      }
      closeModal();
    }
  );
  setTimeout(function () {
    if (!netModal) return;
    Array.prototype.forEach.call(netModal.querySelectorAll('[data-ri-toggle]'), function (btn) {
      btn.addEventListener('click', function () {
        var i = parseInt(btn.getAttribute('data-ri-toggle'));
        if (netReplaceRules[i]) netReplaceRules[i].enabled = !netReplaceRules[i].enabled;
        saveReplaceRules();
        updateReplaceBtn();
        openReplaceModal();
      });
    });
    Array.prototype.forEach.call(netModal.querySelectorAll('[data-ri-del]'), function (btn) {
      btn.addEventListener('click', function () {
        var i = parseInt(btn.getAttribute('data-ri-del'));
        netReplaceRules.splice(i, 1);
        saveReplaceRules();
        updateReplaceBtn();
        openReplaceModal();
      });
    });
    Array.prototype.forEach.call(netModal.querySelectorAll('[data-rscope]'), function (btn) {
      btn.addEventListener('click', function () {
        var s = btn.getAttribute('data-rscope');
        if (s === 'req' || s === 'resp' || s === 'both') {
          netReplaceScope = s;
          saveNetConfig();
          pushReplaceRulesToNative();
          openReplaceModal();
        }
      });
    });
  }, 20);
}

function saveReplaceRules() {
  var st = storageLocal();
  if (st && st.set) {
    try { st.set({ bhNetReplaceRules: netReplaceRules }).catch(function () {}); } catch (e) {}
  }
}

function loadReplaceRules() {
  var st = storageLocal();
  if (!st || !st.get) { finishLoadReplaceRules(); return; }
  try {
    st.get('bhNetReplaceRules').then(function (res) {
      var saved = res && res.bhNetReplaceRules;
      if (Array.isArray(saved)) {
        netReplaceRules = saved.filter(function (r) { return r && r.from; }).map(function (r) {
          return { id: String(r.id || Date.now().toString(36)), from: String(r.from), to: String(r.to || ''), enabled: !!r.enabled };
        });
      }
      finishLoadReplaceRules();
    }).catch(function () { finishLoadReplaceRules(); });
  } catch (e) { finishLoadReplaceRules(); }
}

// 规则加载结束（无论成功/为空/失败）后统一收尾：置已加载标志，刷新按钮，并下发到原生。
// 解开 pushReplaceRulesToNative 的 netReplaceRulesLoaded 守卫，保证此时推的是真实规则。
function finishLoadReplaceRules() {
  netReplaceRulesLoaded = true;
  updateReplaceBtn();
  pushReplaceRulesToNative();
}

// ── 网络配置持久化（拦截开关、过滤状态等）──
var NET_CONFIG_KEY = 'bhNetConfig';

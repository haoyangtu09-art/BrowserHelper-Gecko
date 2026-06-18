// Response suppression and applyReplaceRules execution logic.
function isSuppressResp(status, body) {
  if (status === 204 || status === 205) return true;
  if (!body) return true;
  var t = String(body).trim();
  if (t.length > 64) return false;
  return /^(\{\}|\[\]|0|1|ok|true|false|\s*)$/i.test(t);
}

function applyReplaceRules(text, dir) {
  if (!netReplaceEnabled || !netReplaceRules.length) return text;
  if (netReplaceScope === 'req' && dir === 'resp') return text;
  if (netReplaceScope === 'resp' && dir === 'req') return text;
  var result = text;
  netReplaceRules.forEach(function (rule) {
    if (!rule.enabled || !rule.from) return;
    try {
      // 用简单字符串替换（全部匹配）
      var from = rule.from;
      var to = rule.to || '';
      var out = '';
      var idx = 0;
      while (true) {
        var pos = result.indexOf(from, idx);
        if (pos === -1) { out += result.slice(idx); break; }
        out += result.slice(idx, pos) + to;
        idx = pos + from.length;
      }
      result = out;
    } catch (e) {}
  });
  return result;
}



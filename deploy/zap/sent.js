// ZAP standalone script, the plan's last job (ST-11): prints what budget.js counted, and how many
// requests each active-scan rule sent, so the summary shows which rules ran and how far.
var ScriptVars = Java.type("org.zaproxy.zap.extension.script.ScriptVars");
var Control = Java.type("org.parosproxy.paros.control.Control");
var ExtensionStats = Java.type("org.zaproxy.zap.extension.stats.ExtensionStats");

var counter = ScriptVars.getGlobalCustomVar("budget.sent");
print("[budget] sent " + (counter == null ? 0 : counter.get()));
var stats = Control.getSingleton().getExtensionLoader().getExtension(ExtensionStats.class).getInMemoryStats().getStats("stats.ascan.");
for (var key of stats.keySet()) {
  var rule = /^stats\.ascan\.(\d+)\.urls$/.exec(key);
  if (rule) print("[rule] " + rule[1] + " " + stats.get(key));
}

// ZAP httpsender script (ST-11, test-plan §9's budget guard). ZAP calls sendingRequest for every
// request it sends, from every part of ZAP: the contract import, the active scan and its
// "page not found" probes. ZapScan.kt writes the profile's cap into CAP. At the cap the active scan
// stops, so a profile never sends more than its share of the run's budget (a request already on
// its way from the second thread may still go). The count is an AtomicInteger: the scan runs two
// threads.
var ScriptVars = Java.type("org.zaproxy.zap.extension.script.ScriptVars");
var AtomicInteger = Java.type("java.util.concurrent.atomic.AtomicInteger");
var CAP = __CAP__;

function sendingRequest(msg, initiator, helper) {
  var counter = ScriptVars.getGlobalCustomVar("budget.sent");
  if (counter == null) {
    ScriptVars.setGlobalCustomVar("budget.sent", new AtomicInteger());
    counter = ScriptVars.getGlobalCustomVar("budget.sent");
  }
  if (counter.incrementAndGet() == CAP - 1) {
    var Control = Java.type("org.parosproxy.paros.control.Control");
    var ExtensionActiveScan = Java.type("org.zaproxy.zap.extension.ascan.ExtensionActiveScan");
    Control.getSingleton().getExtensionLoader().getExtension(ExtensionActiveScan.class).stopAllScans();
    print("[budget] cap of " + CAP + " requests reached: the active scan stops");
  }
}

function responseReceived(msg, initiator, helper) {}

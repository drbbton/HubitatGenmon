preferences {
    input name: "IP", type: "string", title: "Genmon Host IP", required: true
    input name: "genPort", type: "number", title: "Genmon Port", required: true, defaultValue: 8000,
        description: "<em>Default is 8000. Check your genmon.conf if you changed it.</em>"
    input name: "username", type: "string", title: "Username", required: false,
        description: "<em>Leave blank if Genmon auth is disabled (recommended for local use).</em>"
    input name: "password", type: "password", title: "Password", required: false
    input name: "enableRemoteCommands", type: "bool", title: "Enable Remote Commands (start/stop/exercise)?",
        defaultValue: false, description: "<em>Allows sending start, stop, and exercise commands to the generator. Disabled by default — use with caution.</em>"
    input name: "interval", type: "enum", title: "Polling Interval", required: true,
        options: ["1", "5", "10", "15", "30"], defaultValue: "5"
    input name: "debugEnable", type: "bool", title: "Enable debug logging?", defaultValue: true,
        description: "<em>Auto-disables after 2 hours.</em>"
}

metadata {
    definition (
        name: "Hubitat Genmon Generator Monitor",
        namespace: "drbbton",
        author: "drbbton",
        importUrl: "https://raw.githubusercontent.com/drbbton/HubitatGenmon/main/genmondriver.groovy"
    ) {
        capability "Polling"
        capability "Refresh"
        capability "PowerMeter"     // power in watts
        capability "Switch"         // on = generator running, off = idle/stopped

        attribute "generatorStatus",    "string"   // Engine State (Idle, Running, Exercising, Fault, …)
        attribute "switchState",        "string"   // Auto / Manual / Off (generator switch panel position)
        attribute "batteryVoltage",     "number"   // V
        attribute "batteryCurrent",     "number"   // mA
        attribute "rpm",                "number"
        attribute "frequency",          "number"   // Hz
        attribute "temperature",        "number"   // °F
        attribute "outputVoltage",      "number"   // V
        attribute "outputCurrent",      "number"   // A
        attribute "utilityVoltage",     "number"   // V
        attribute "transferSwitchState","string"   // Utility Power / Generator Power
        attribute "outage",             "string"   // Yes / No
        attribute "runHours",           "number"   // total engine hours
        attribute "fuelLevel",          "number"   // % (requires fuel sensor)
        attribute "fuelUsedLast30Days", "number"   // gallons
        attribute "lastAlarm",          "string"
        attribute "lastAction",         "string"
        attribute "serviceADue",        "string"   // e.g. "192 hrs or 05/02/2027"
        attribute "serviceBDue",        "string"
        attribute "batteryCheckDue",    "string"
        attribute "statusSummary",      "string"   // human-readable one-liner for dashboards

        command "start"
        command "stop"
        command "startExercise"
    }
}

def installed() {
    log.info "Genmon driver installed"
    sendEvent(name: "switch", value: "off")
    sendEvent(name: "generatorStatus", value: "Unknown")
}

def uninstalled() {
    unschedule()
}

def updated() {
    log.info "Genmon driver updated — rescheduling poll every ${interval} minute(s)"
    unschedule()
    if (debugEnable) runIn(7200, disableDebug)
    schedulePolling()
    poll()
}

def disableDebug() {
    log.info "Timed elapsed, disabling debug logging"
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
}

def refresh() { poll() }

def schedulePolling() {
    if (interval == "1")       runEvery1Minute(poll)
    else if (interval == "5")  runEvery5Minutes(poll)
    else if (interval == "10") runEvery10Minutes(poll)
    else if (interval == "15") runEvery15Minutes(poll)
    else if (interval == "30") runEvery30Minutes(poll)
}

def poll() {
    fetchStatus()
    fetchMaintenance()
    fetchOutage()
}

// ─── HTTP helpers ────────────────────────────────────────────────────────────

private String baseUrl() { "http://${IP}:${genPort}" }

private Map buildHeaders() {
    def headers = ["Accept": "application/json"]
    if (username && password) {
        def creds = "${username}:${password}".bytes.encodeBase64().toString()
        headers["Authorization"] = "Basic ${creds}"
    }
    return headers
}

// ─── Status fetch & parse ────────────────────────────────────────────────────

def fetchStatus() {
    def params = [uri: "${baseUrl()}/cmd/status_json", headers: buildHeaders(), timeout: 15]
    try {
        httpGet(params) { resp ->
            if (debugEnable) log.debug "status_json raw: ${resp.data}"
            parseStatusJson(resp.data)
        }
    } catch (e) {
        log.error "Could not reach Genmon at ${baseUrl()}: ${e.message}"
        evtIfChanged("generatorStatus", "Unreachable")
        evtIfChanged("statusSummary", "Unreachable")
    }
}

def fetchMaintenance() {
    def params = [uri: "${baseUrl()}/cmd/maint_json", headers: buildHeaders(), timeout: 15]
    try {
        httpGet(params) { resp ->
            if (debugEnable) log.debug "maint_json raw: ${resp.data}"
            parseMaintenanceJson(resp.data)
        }
    } catch (e) {
        log.warn "Could not fetch maintenance data: ${e.message}"
    }
}

def fetchOutage() {
    def params = [uri: "${baseUrl()}/cmd/outage_json", headers: buildHeaders(), timeout: 15]
    try {
        httpGet(params) { resp ->
            if (debugEnable) log.debug "outage_json raw: ${resp.data}"
            parseOutageJson(resp.data)
        }
    } catch (e) {
        log.warn "Could not fetch outage data: ${e.message}"
    }
}

private void parseStatusJson(data) {
    def flat = [:]
    flattenGenmonData(data, flat)
    if (debugEnable) log.debug "Flattened status map: ${flat}"

    def engineState  = flat["Engine State"] ?: "Unknown"
    def swState      = flat["Switch State"] ?: "Unknown"
    def battV        = parseNumeric(flat["Battery Voltage"])
    def battA        = parseNumericMilliamps(flat["Battery Current"])
    def rpmVal       = parseNumeric(flat["RPM"])
    def freqVal      = parseNumeric(flat["Frequency"])
    def tempVal      = parseNumeric(flat["Temperature"])
    def outV         = parseNumeric(flat["Output Voltage"])
    def outA         = parseNumeric(flat["Output Current"])
    def outKW        = parseNumeric(flat["Output Power (Single Phase)"] ?: flat["Output Power (Three Phase)"] ?: flat["Output Power"])
    // Genmon labels this "Line Utility Voltage" on Evolution controllers
    def utilV        = parseNumeric(flat["Line Utility Voltage"] ?: flat["Utility Voltage"])
    def xferState    = flat["Transfer Switch State"] ?: ""
    def lastAction   = flat["Last Action"] ?: ""

    def running = engineState?.toLowerCase() in ["running", "exercising", "warming up", "cool down", "cooldown", "starting", "cranking"]

    evtIfChanged("generatorStatus",    engineState)
    evtIfChanged("switchState",        swState)
    evtIfChanged("switch",             running ? "on" : "off")
    if (battV   != null) evtIfChanged("batteryVoltage",  battV.round(2))
    if (battA   != null) evtIfChanged("batteryCurrent",  battA)
    if (rpmVal  != null) evtIfChanged("rpm",             rpmVal.toInteger())
    if (freqVal != null) evtIfChanged("frequency",       freqVal.round(2))
    if (tempVal != null) evtIfChanged("temperature",     tempVal.round(1))
    if (outV    != null) evtIfChanged("outputVoltage",   outV.round(0).toInteger())
    if (outA    != null) evtIfChanged("outputCurrent",   outA.round(2))
    if (outKW   != null) evtIfChanged("power",           (outKW * 1000).toInteger())   // kW → W
    if (utilV   != null) evtIfChanged("utilityVoltage",  utilV.round(0).toInteger())
    if (xferState)       evtIfChanged("transferSwitchState", xferState)
    if (lastAction)      evtIfChanged("lastAction",      lastAction.trim())

    def summary = engineState
    if (running && outKW != null) summary += " | ${outKW} kW"
    if (utilV != null)            summary += " | Grid: ${utilV.round(0).toInteger()}V"
    evtIfChanged("statusSummary", summary)

    // Surface most recent alarm if present
    def alarm = flat["Last Alarm Log"] ?: ""
    if (alarm && alarm.trim() != "") evtIfChanged("lastAlarm", alarm.trim())
}

private void parseMaintenanceJson(data) {
    def flat = [:]
    flattenGenmonData(data, flat)
    if (debugEnable) log.debug "Flattened maint map: ${flat}"

    def runHrs = parseNumeric(flat["Total Run Hours"] ?: flat["Run Hours"] ?: flat["Hours of Protection"])
    if (runHrs != null) evtIfChanged("runHours", runHrs.round(1))

    def fuel = parseNumeric(flat["Fuel Level"] ?: flat["Fuel Tank Level"] ?: flat["Tank Level"])
    if (fuel != null) evtIfChanged("fuelLevel", fuel.round(0).toInteger())

    def fuelUsed = parseNumeric(flat["Fuel Used Last 30 Days"] ?: flat["Fuel Consumption"])
    if (fuelUsed != null) evtIfChanged("fuelUsedLast30Days", fuelUsed.round(2))

    def svcA = flat["Service A Due"] ?: flat["Next Service A"]
    if (svcA) evtIfChanged("serviceADue", svcA.trim())

    def svcB = flat["Service B Due"] ?: flat["Next Service B"]
    if (svcB) evtIfChanged("serviceBDue", svcB.trim())

    def battCheck = flat["Battery Check Due"] ?: flat["Next Battery Check"]
    if (battCheck) evtIfChanged("batteryCheckDue", battCheck.trim())
}

private void parseOutageJson(data) {
    def flat = [:]
    flattenGenmonData(data, flat)
    if (debugEnable) log.debug "Flattened outage map: ${flat}"

    // "Outage" field is "Yes" / "No" — also check "Current Outage"
    def outageVal = flat["Outage"] ?: flat["Current Outage"] ?: flat["Utility Loss"]
    if (outageVal) evtIfChanged("outage", outageVal.trim())
}

// Genmon returns nested list-of-dicts: [{Section: [{Key: Value}, ...]}, ...]
// Recursively flatten everything into a single key→value map.
private void flattenGenmonData(data, Map result) {
    if (data instanceof List) {
        data.each { item -> flattenGenmonData(item, result) }
    } else if (data instanceof Map) {
        data.each { k, v ->
            if (v instanceof List || v instanceof Map) {
                flattenGenmonData(v, result)
            } else {
                result[k.toString()] = v?.toString()
            }
        }
    }
}

// Extract first decimal/integer from strings like "13.60 V", "247 V", "245.5 h"
private Double parseNumeric(String val) {
    if (!val) return null
    def m = (val =~ /([0-9]+\.?[0-9]*)/)
    return m ? m[0][1].toDouble() : null
}

// Battery current comes as "116 mA" — return integer mA value
private Integer parseNumericMilliamps(String val) {
    def d = parseNumeric(val)
    return d != null ? d.toInteger() : null
}

private void evtIfChanged(String name, value) {
    def current = device.currentValue(name)?.toString()
    if (current != value?.toString()) {
        sendEvent(name: name, value: value)
        if (debugEnable) log.debug "${name}: ${current} → ${value}"
    }
}

// ─── Remote commands ─────────────────────────────────────────────────────────

def on()  { start() }
def off() { stop() }

def start() {
    if (!enableRemoteCommands) { log.warn "Remote commands are disabled in preferences"; return }
    log.info "Sending remote START to generator"
    sendRemoteCommand("start")
    runIn(5, poll)
}

def stop() {
    if (!enableRemoteCommands) { log.warn "Remote commands are disabled in preferences"; return }
    log.info "Sending remote STOP to generator"
    sendRemoteCommand("stop")
    runIn(5, poll)
}

def startExercise() {
    if (!enableRemoteCommands) { log.warn "Remote commands are disabled in preferences"; return }
    log.info "Sending remote STARTEXERCISE to generator"
    sendRemoteCommand("startexercise")
    runIn(5, poll)
}

private void sendRemoteCommand(String cmd) {
    def params = [
        uri: "${baseUrl()}/cmd/setremote",
        headers: buildHeaders(),
        query: [setremote: cmd],
        timeout: 15
    ]
    try {
        httpGet(params) { resp ->
            def body = resp.data?.toString() ?: ""
            if (debugEnable) log.debug "Remote '${cmd}' response: ${body}"
            if (body.toLowerCase().contains("success")) {
                log.info "Remote command '${cmd}' acknowledged by Genmon"
            } else {
                log.warn "Remote command '${cmd}' unexpected response: ${body}"
            }
        }
    } catch (e) {
        log.error "Failed to send remote command '${cmd}': ${e.message}"
    }
}

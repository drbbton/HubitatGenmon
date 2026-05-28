# Hubitat Genmon Generator Monitor

Hubitat device driver for monitoring a generator running [Genmon](https://github.com/jgyates/genmon) (generator monitor software for Raspberry Pi). Polls the Genmon REST API to surface generator state, power output, battery health, fuel level, and more directly in your Hubitat hub.

## Features

- **Generator status** — Idle, Running, Exercising, Warming Up, Cool Down, Fault
- **Power monitoring** — output kW, voltage, current (PowerMeter capability)
- **Battery voltage** — track 12V starter battery health
- **RPM & frequency** — real-time engine metrics
- **Utility voltage & transfer switch state** — know when the grid is up or down
- **Run hours** — total engine hours from maintenance data
- **Fuel level** — percentage, if a fuel level sensor is connected to your Genmon
- **Last alarm** — surfaces the most recent alarm log entry
- **Dashboard summary attribute** — single `statusSummary` string for easy tiles
- **Switch capability** — `on`/`off` maps to generator running/stopped
- **Optional remote commands** — start, stop, exercise (disabled by default)
- **Configurable poll interval** — 1, 5, 10, 15, or 30 minutes
- **Debug logging** with auto-disable after 2 hours

## Requirements

- A generator with [Genmon](https://github.com/jgyates/genmon) installed and running on your local network
- Genmon's web server accessible from the Hubitat hub (default port 8000)
- Hubitat hub with network access to the Genmon host

## Genmon Configuration

This driver uses the Genmon HTTP REST API. The default Genmon install listens on port 8000 with auth **disabled** — no changes needed for most home setups. If you have enabled Genmon authentication:

- The driver supports **HTTP Basic Auth** (username + password preferences). This works only if Genmon is configured with basic auth and **not** HTTPS. Session-based auth (the Genmon web UI login form) is not supported.
- The simplest approach for local network use is to leave Genmon auth **disabled** in `genmon.conf`.

## Installation

### Option A — Hubitat Package Manager (recommended)

1. Open HPM on your Hubitat hub
2. Search for **Genmon** or install from URL:
   ```
   https://raw.githubusercontent.com/drbbton/HubitatGenmon/main/packageManifest.json
   ```

### Option B — Manual

1. In Hubitat, go to **Drivers Code → + New Driver**
2. Paste the contents of [`genmondriver.groovy`](genmondriver.groovy) and click **Save**
3. Go to **Devices → + Add Virtual Device**, choose **Hubitat Genmon Generator Monitor** as the type, and save

## Configuration

| Preference | Description |
|---|---|
| **Genmon Host IP** | IP address of the machine running Genmon |
| **Genmon Port** | Port Genmon listens on (default: `8000`) |
| **Username / Password** | Only needed if Genmon auth is enabled; leave blank otherwise |
| **Enable Remote Commands** | Allows `start`, `stop`, and `exercise` commands. Disabled by default. |
| **Polling Interval** | How often to fetch status (1–30 minutes) |
| **Enable debug logging** | Verbose logging in Hubitat logs; auto-disables after 2 hours |

## Attributes

| Attribute | Type | Description |
|---|---|---|
| `generatorStatus` | string | Engine state (Idle, Running, Exercising, Fault, …) |
| `switchState` | string | Generator switch panel position (Auto/Manual/Off) |
| `switch` | string | `on` if running, `off` if idle/stopped |
| `power` | number | Output power in **watts** (PowerMeter capability) |
| `batteryVoltage` | number | Starter battery voltage in V |
| `batteryCurrent` | number | Starter battery current in mA |
| `temperature` | number | Generator ambient/engine temperature in °F |
| `rpm` | number | Engine RPM |
| `frequency` | number | Output frequency in Hz |
| `outputVoltage` | number | Generator output voltage in V |
| `outputCurrent` | number | Generator output current in A |
| `utilityVoltage` | number | Utility/grid voltage in V |
| `transferSwitchState` | string | `Utility Power` or `Generator Power` |
| `outage` | string | `Yes` if utility is out, `No` if utility is present |
| `runHours` | number | Total engine run hours |
| `fuelLevel` | number | Fuel tank level in % (requires fuel sensor) |
| `fuelUsedLast30Days` | number | Fuel consumed in the last 30 days (gallons) |
| `lastAlarm` | string | Most recent alarm log entry |
| `lastAction` | string | Timestamp and description of the last generator action |
| `serviceADue` | string | Service A due date/hours (e.g. `192 hrs or 05/02/2027`) |
| `serviceBDue` | string | Service B due date/hours |
| `batteryCheckDue` | string | Battery check due date |
| `statusSummary` | string | Dashboard-friendly one-liner (e.g. `Running | 5.2 kW | Grid: 0V`) |

## Commands

| Command | Description |
|---|---|
| `poll` / `refresh` | Fetch current status immediately |
| `start` | Remote start (requires **Enable Remote Commands**) |
| `stop` | Remote stop (requires **Enable Remote Commands**) |
| `startExercise` | Start an exercise run (requires **Enable Remote Commands**) |
| `on` / `off` | Aliases for `start` / `stop` (Switch capability) |

## Using in Rules / Dashboards

A few useful Rule Machine trigger ideas:

- **Notify when generator starts** — trigger on `generatorStatus` changing to `Running`
- **Notify on fault** — trigger on `generatorStatus` changing to `Fault`
- **Notify when utility power returns** — trigger on `transferSwitchState` changing to `Utility Power`
- **Low battery alert** — trigger on `batteryVoltage` dropping below `12.2`
- **Low fuel alert** — trigger on `fuelLevel` dropping below `25`
- **Outage alert** — trigger on `outage` changing to `Yes`
- **Service reminder** — trigger on `serviceADue` or `serviceBDue` changing (e.g. hours threshold crossed)

The `statusSummary` attribute works well as a single-attribute dashboard tile since it combines state + power + grid voltage into one string.

## Troubleshooting

**Driver shows "Unreachable"**
- Verify the Genmon web interface is accessible in a browser at `http://<IP>:8000`
- Check that the Hubitat hub can reach that IP (same VLAN, no firewall blocking)

**All attributes show "Unknown" after polling**
- Enable debug logging and check the Hubitat log for the raw JSON from Genmon
- The driver flattens Genmon's nested JSON structure; the raw output in the log will show if keys differ from expected names on your Genmon version

**Fuel level not appearing**
- Fuel level requires a hardware fuel sensor connected to your Genmon setup
- If no sensor is present, Genmon won't include the field and the attribute will not be set

**Remote commands not working**
- Ensure **Enable Remote Commands** is checked in preferences
- Verify your generator controller supports remote start (most Generac Evolution controllers do; Nexus controllers may not)
- Check the Hubitat log for the response from Genmon

## Notes

- Communication is HTTP only; HTTPS is not supported
- Genmon does not push notifications — the driver is poll-only
- Tested against Genmon running on a Raspberry Pi with a Generac Evolution controller; other supported controllers should work but field names may vary slightly

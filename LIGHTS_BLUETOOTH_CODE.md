# Lights and Bluetooth Control Code

This document brings together the existing source files for lighting controls, HC-05 Bluetooth Classic, and Arduino relay control. It is a single reference document, not one executable file: keep each section in its original path below because the React app, Android Bluetooth bridge, and Arduino firmware run on different platforms.

For real hardware, use the native Android app and follow the wiring and safety instructions in the final section. Browser mode is simulation only. Life-safety/emergency lighting is excluded from general relay control.

## `src/data/lightingZones.js`

```javascript
export const ZONE_CATEGORIES = {
  residential: "Residential",
  common: "Common area",
  safety: "Life safety",
  utility: "Utility",
  commercial: "Commercial",
  outdoor: "Outdoor",
  exterior: "Exterior",
  additional: "Additional area",
};

const RELAY_PIN_BY_ZONE = {
  "res-3bhk": 2,
  "res-4bhk": 3,
  clubhouse: 4,
  service: 5,
  cafe: 6,
  podium: 7,
  balcony: 8,
  landscape: 9,
  facade: 12,
  additional: 13,
};

export const DEFAULT_ZONES = [
  { id: "res-3bhk", name: "3 BHK", category: "residential", image: "balcony" },
  { id: "res-4bhk", name: "4 BHK", category: "residential", image: "balcony" },
  { id: "clubhouse", name: "Clubhouse", category: "common", image: "clubhouse" },
  { id: "emergency", name: "Refuse / Emergency Exit", category: "safety", image: "emergency" },
  { id: "service", name: "Service Floor", category: "utility", image: "service" },
  { id: "cafe", name: "Cafe", category: "commercial", image: "cafe" },
  { id: "podium", name: "Podium", category: "outdoor", image: "podium" },
  { id: "balcony", name: "Swimming Pool", category: "outdoor", image: "balcony" },
  { id: "landscape", name: "Landscape", category: "outdoor", image: "landscape" },
  { id: "facade", name: "Animation", category: "exterior", image: "facade" },
  { id: "additional", name: "Additional Area", category: "additional", image: "service" },
].map((zone, index) => ({
  ...zone,
  controller: `CTRL-${String(Math.floor(index / 4) + 1).padStart(2, "0")}`,
  relayPin: RELAY_PIN_BY_ZONE[zone.id] ?? null,
  enabled: zone.category === "safety",
  brightness: zone.category === "safety" ? 100 : 72,
  color: "#eac28a",
  effect: "steady",
  lastCommand: "Ready · simulation",
  updatedAt: null,
}));

export function mergeSavedZones(savedZones) {
  const savedById = new Map(savedZones.map((zone) => [zone.id, zone]));
  const defaultIds = new Set(DEFAULT_ZONES.map((zone) => zone.id));
  return [
    ...DEFAULT_ZONES.map((zone) => ({
      ...zone,
      ...(savedById.get(zone.id) || {}),
      controller: zone.controller,
      relayPin: zone.relayPin,
    })),
    ...savedZones.filter((zone) => !defaultIds.has(zone.id)),
  ];
}

export const OUTDOOR_ZONE_IDS = ["podium", "balcony", "landscape"];
export const RESIDENTIAL_ZONE_IDS = ["res-3bhk", "res-4bhk"];
export const COMMON_ZONE_IDS = ["clubhouse"];
export const PROTECTED_ZONE_IDS = ["emergency"];

export const ZONE_IMAGES = {
  clubhouse: "/assets/clubhouse.jpg",
  cafe: "/assets/cafe.jpg",
  podium: "/assets/podium.jpg",
  balcony: "/assets/balcony.jpg",
  landscape: "/assets/landscape.jpg",
  facade: "/assets/facade.jpg",
  emergency: "/assets/podium.jpg",
  service: "/assets/podium.jpg",
};
```

## `src/services/lightingService.js`

```javascript
import { Capacitor, registerPlugin } from "@capacitor/core";

const CLASSIC_BT = registerPlugin("ClassicBluetooth");

export function relayStateForLightingState(state) {
  if (state !== "ON" && state !== "OFF") {
    throw new Error("Unsupported lighting state.");
  }
  return state === "ON" ? "OFF" : "ON";
}

export function relayWatchdogSupported(reply) {
  return reply?.acknowledged === true
    && reply.watchdog === true
    && reply.timeoutMs === 3000;
}

export async function probeRelayWatchdog() {
  if (!Capacitor.isNativePlatform() || Capacitor.getPlatform() !== "android") {
    throw new Error("Relay disconnect failsafe is available in the Android app.");
  }
  const reply = await CLASSIC_BT.probeWatchdog();
  if (!relayWatchdogSupported(reply)) {
    throw new Error("Upload the updated Mega sketch to enable the relay disconnect failsafe.");
  }
  return reply;
}

export async function sendRelayHeartbeat() {
  if (!Capacitor.isNativePlatform() || Capacitor.getPlatform() !== "android") {
    throw new Error("Relay heartbeat is available in the Android app.");
  }
  return CLASSIC_BT.sendHeartbeat();
}

export async function sendZoneCommand(connection, zone, state, options = {}) {
  if (state !== "ON" && state !== "OFF") {
    throw new Error("Unsupported lighting state.");
  }
  if (zone.category === "safety" && state === "OFF" && !options.maintenanceAuthorized) {
    throw new Error("Emergency exit lighting is protected; a restricted maintenance test is required.");
  }
  if (zone.category === "safety" && connection?.mode !== "simulation") {
    throw new Error("Life-safety lighting is not controlled by this general-purpose Bluetooth relay system.");
  }
  if (connection?.mode === "simulation") {
    return {
      mode: "simulation",
      acknowledged: true,
      actualState: state,
      status: `Simulation · ${state} (not sent to hardware)`,
    };
  }
  if (connection?.mode === "bluetooth") return sendBluetoothCommand(zone, state, options);
  throw new Error("Connect the paired HC-05 Bluetooth Classic controller before sending hardware commands.");
}

export async function sendMasterCommand(connection, zones, state, options = {}) {
  const relayState = relayStateForLightingState(state);
  const allowedZones = zones.filter((zone) => zone.category !== "safety");
  if (connection?.mode === "bluetooth") {
    if (!Capacitor.isNativePlatform() || Capacitor.getPlatform() !== "android") {
      throw new Error("Lighting commands over Classic Bluetooth are available in the Android app.");
    }

    const reply = await CLASSIC_BT.sendMasterCommand({
      zoneIds: allowedZones.map((zone) => zone.id),
      state: relayState,
    });
    if (
      reply?.error === "invalid_command"
      && reply.zoneId === ""
      && reply.state === relayState
    ) {
      return sendZonesSequentially(connection, allowedZones, state, options);
    }
    if (!Array.isArray(reply?.results) || reply.state !== relayState) {
      throw new Error("The controller returned an incomplete master-command response.");
    }
    const resultsByZone = new Map(
      reply.results.map((result) => [result.zoneId, result]),
    );
    const results = allowedZones.map((zone) => {
      const result = resultsByZone.get(zone.id);
      const acknowledged = result?.acknowledged === true
        && result.state === relayState
        && result.zoneId === zone.id;
      return {
        mode: acknowledged ? "bluetooth" : "error",
        acknowledged,
        actualState: acknowledged ? state : null,
        status: acknowledged
          ? `Controller replied · requested ${state} (relay command ${relayState}).`
          : `Controller response missing · ${result?.error || "No matching zone result returned."} Physical state is unknown.`,
      };
    });
    return { allowedZones, results };
  }

  return sendZonesSequentially(connection, allowedZones, state, options);
}

async function sendZonesSequentially(connection, allowedZones, state, options) {
  const results = [];
  for (let index = 0; index < allowedZones.length; index += 1) {
    const zone = allowedZones[index];
    try {
      results.push(await sendZoneCommand(connection, zone, state, options));
    } catch (error) {
      results.push({
        mode: "error",
        acknowledged: false,
        actualState: null,
        status: `Controller error · ${error.message}`,
      });
      if (connection?.mode === "bluetooth") {
        for (const unsentZone of allowedZones.slice(index + 1)) {
          results.push({
            mode: "error",
            acknowledged: false,
            actualState: null,
            status: `Not sent · previous controller request for ${zone.name} failed; physical state is unknown.`,
          });
        }
        break;
      }
    }
  }
  return { allowedZones, results };
}

export async function scanBluetoothDevices(onDeviceFound) {
  if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === "android") {
    const listener = onDeviceFound
      ? await CLASSIC_BT.addListener("deviceFound", onDeviceFound)
      : null;
    try {
      const result = await CLASSIC_BT.scanDevices();
      return result.devices || [];
    } finally {
      await listener?.remove();
    }
  }
  throw new Error(
    "HC-05 and HC-06 require Android Bluetooth Classic SPP. The browser cannot scan for Bluetooth Classic; use the Android app.",
  );
}

export async function connectBluetoothDevice(device, onDisconnected) {
  if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === "android") {
    const listener = onDisconnected
      ? await CLASSIC_BT.addListener("connectionLost", onDisconnected)
      : null;
    try {
      const result = await CLASSIC_BT.connect({ address: device.address || device.id });
      return { ...result, controlReady: true, listener };
    } catch (error) {
      await listener?.remove();
      throw error;
    }
  }
  throw new Error("Classic Bluetooth controllers are supported by the native Android app, not by a web browser.");
}

export async function disconnectBluetooth() {
  if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === "android") {
    return CLASSIC_BT.disconnect();
  }
  return { disconnected: true };
}

async function sendBluetoothCommand(zone, state, options = {}) {
  if (!Capacitor.isNativePlatform() || Capacitor.getPlatform() !== "android") {
    throw new Error("Lighting commands over Classic Bluetooth are available in the Android app.");
  }
  const relayState = relayStateForLightingState(state);
  let reply;
  try {
    reply = await CLASSIC_BT.sendCommand({
      zoneId: zone.id,
      state: relayState,
      maintenanceAuthorized: Boolean(options.maintenanceAuthorized),
      brightness: zone.brightness,
      color: zone.color,
      effect: zone.effect,
    });
  } catch (error) {
    throw new Error(error.message || "The Bluetooth controller did not reply to the command.");
  }
  if (reply?.acknowledged !== true || reply.state !== relayState || reply.zoneId !== zone.id) {
    throw new Error("The controller reply did not match the requested zone and state; physical state is unknown.");
  }
  return {
    mode: "bluetooth",
    acknowledged: true,
    actualState: state,
    status: `Controller replied · requested ${state} (relay command ${relayState}).`,
  };
}

```

## `src/hooks/useLighting.js`

```javascript
import { Capacitor } from "@capacitor/core";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  COMMON_ZONE_IDS,
  DEFAULT_ZONES,
  mergeSavedZones,
  OUTDOOR_ZONE_IDS,
  PROTECTED_ZONE_IDS,
  RESIDENTIAL_ZONE_IDS,
} from "../data/lightingZones.js";
import {
  connectBluetoothDevice,
  disconnectBluetooth,
  probeRelayWatchdog,
  scanBluetoothDevices,
  sendRelayHeartbeat,
  sendMasterCommand,
  sendZoneCommand,
} from "../services/lightingService.js";
import { getDefaultScenes } from "../services/sceneService.js";
import { readStorage, writeStorage } from "../services/storage.js";

const LOG_LIMIT = 180;
const BLUETOOTH_DEVICE_STORAGE_KEY = "beacon.bluetoothDevice";
const AUTO_RECONNECT_LIMIT = 3;
const AUTO_RECONNECT_DELAY_MS = 1500;

function getSavedBluetoothDevice() {
  const saved = readStorage(BLUETOOTH_DEVICE_STORAGE_KEY, null);
  return saved && typeof saved.address === "string" && saved.address ? saved : null;
}

const initialZones = () => {
  const savedZones = readStorage("beacon.zones", DEFAULT_ZONES);
  return mergeSavedZones(savedZones).map((zone) => ({
    ...zone,
    enabled: zone.category === "safety" ? true : zone.enabled,
    pending: false,
    commandState: zone.category === "safety" ? "simulated" : zone.enabled ? "simulated" : "idle",
    lastCommand: zone.category === "safety" ? "Simulation · protected safety lighting" : zone.enabled ? "Simulation · ON" : "Ready · simulation",
  }));
};

export function useLighting() {
  const [zones, setZones] = useState(initialZones);
  const [logs, setLogs] = useState(() => readStorage("beacon.logs", []));
  const [connection, setConnection] = useState({
    mode: "simulation",
    bluetoothDevice: null,
    bluetoothError: "",
    relayWatchdogReady: false,
  });
  const [devices, setDevices] = useState([]);
  const [bluetoothBusy, setBluetoothBusy] = useState(false);
  const [maintenancePin, setMaintenancePin] = useState("");
  const [maintenanceActive, setMaintenanceActive] = useState(false);
  const [eventMessage, setEventMessage] = useState("");
  const messageTimer = useRef(null);
  const bluetoothDisconnectListener = useRef(null);
  const [lastBluetoothDevice, setLastBluetoothDevice] = useState(getSavedBluetoothDevice);
  const autoConnectStarted = useRef(false);
  const autoReconnectAttempts = useRef(0);
  const autoReconnectTimer = useRef(null);
  const connectionStableTimer = useRef(null);
  const connectionLossPending = useRef(false);
  const intentionalDisconnect = useRef(false);
  const connectDeviceRef = useRef(null);

  const appendLog = useCallback((entry) => {
    const log = {
      id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      time: new Date().toISOString(),
      ...entry,
    };
    setLogs((current) => {
      const next = [log, ...current].slice(0, LOG_LIMIT);
      writeStorage("beacon.logs", next);
      return next;
    });
  }, []);

  const notify = useCallback(
    (message, tone = "info", source = connection.mode) => {
      setEventMessage(message);
      clearTimeout(messageTimer.current);
      messageTimer.current = setTimeout(() => setEventMessage(""), 3800);
      appendLog({ action: message, tone, source });
    },
    [appendLog, connection.mode],
  );

  useEffect(() => {
    writeStorage("beacon.zones", zones.map(({ pending, ...zone }) => zone));
  }, [zones]);

  useEffect(() => () => {
    clearTimeout(messageTimer.current);
    clearTimeout(autoReconnectTimer.current);
    clearTimeout(connectionStableTimer.current);
    bluetoothDisconnectListener.current?.remove();
  }, []);

  useEffect(() => {
    if (connection.mode !== "bluetooth" || !connection.relayWatchdogReady) return undefined;
    let active = true;
    let heartbeatTimer;
    const sendHeartbeat = async () => {
      try {
        await sendRelayHeartbeat();
      } catch (error) {
        if (!active) return;
        setConnection((current) => (
          current.mode === "bluetooth"
            ? { ...current, relayWatchdogReady: false, bluetoothError: error.message }
            : current
        ));
        notify(`Relay heartbeat failed; the Arduino watchdog will switch Additional Area off: ${error.message}`, "error");
        return;
      }
      if (active) heartbeatTimer = setTimeout(sendHeartbeat, 1000);
    };
    heartbeatTimer = setTimeout(sendHeartbeat, 1000);
    return () => {
      active = false;
      clearTimeout(heartbeatTimer);
    };
  }, [connection.mode, connection.relayWatchdogReady, notify]);

  const setZoneState = useCallback((zoneId, patch) => {
    setZones((current) =>
      current.map((zone) => (zone.id === zoneId ? { ...zone, ...patch } : zone)),
    );
  }, []);

  const markHardwareStatesUnknown = useCallback(() => {
    setZones((current) => current.map((zone) => ({
      ...zone,
      enabled: zone.category === "safety",
      pending: false,
      commandState: "idle",
      lastCommand: "Hardware state has not been queried.",
    })));
  }, []);

  const returnToSafeSimulation = useCallback(() => {
    setZones((current) => current.map((zone) => ({
      ...zone,
      enabled: zone.category === "safety",
      pending: false,
      commandState: zone.category === "safety" ? "simulated" : "idle",
      lastCommand: zone.category === "safety"
        ? "Simulation · protected safety lighting"
        : "Ready · simulation",
    })));
  }, []);

  const resetZones = useCallback(async () => {
    if (connection.mode === "bluetooth") {
      try {
        intentionalDisconnect.current = true;
        await disconnectBluetooth();
        await bluetoothDisconnectListener.current?.remove();
        bluetoothDisconnectListener.current = null;
      } catch (error) {
        notify(`Cannot safely reset while the Bluetooth controller is connected: ${error.message}`, "error");
        return false;
      } finally {
        intentionalDisconnect.current = false;
      }
    }
    setZones(DEFAULT_ZONES.map((zone) => ({
      ...zone,
      pending: false,
      commandState: zone.category === "safety" ? "simulated" : "idle",
      lastCommand: zone.category === "safety" ? "Simulation · protected safety lighting" : "Ready · simulation",
    })));
    setConnection({
      mode: "simulation",
      bluetoothDevice: null,
      bluetoothError: "",
      relayWatchdogReady: false,
    });
    setMaintenanceActive(false);
    setMaintenancePin("");
    appendLog({ action: "Simulator reset · defaults restored · no hardware commands sent", tone: "simulation", source: "simulation" });
    notify("Simulator defaults restored; no hardware commands were sent.", "simulation", "simulation");
    return true;
  }, [appendLog, connection.mode, notify]);

  const clearHistory = useCallback(() => {
    writeStorage("beacon.logs", []);
    setLogs([]);
  }, []);

  const sendZone = useCallback(
    async (zoneId, state, options = {}) => {
      const zone = zones.find((item) => item.id === zoneId);
      if (!zone || zone.pending) return false;
      if (zone.category === "safety" && state === "OFF" && (!maintenanceActive || !options.maintenanceAuthorized)) {
        notify("Emergency-exit lighting is protected; ordinary commands cannot switch it off.", "warning");
        return false;
      }

      setZoneState(zoneId, { pending: true, commandState: "sending", lastCommand: "Command sent · awaiting acknowledgement" });
      appendLog({ action: `${zone.name} · ${state} · command sent`, tone: "info", source: connection.mode });
      try {
        const commandZone = options.settings ? { ...zone, ...options.settings } : zone;
        const result = await sendZoneCommand(connection, commandZone, state, {
          ...options,
          maintenanceAuthorized: Boolean(maintenanceActive && options.maintenanceAuthorized),
        });
        setZoneState(zoneId, {
          ...(options.settings || {}),
          enabled: result.actualState === "ON",
          pending: false,
          commandState: result.mode === "simulation" ? "simulated" : "acknowledged",
          lastCommand: result.status,
          updatedAt: new Date().toISOString(),
        });
        appendLog({
          action: `${zone.name} · ${result.status}`,
          tone: result.mode === "simulation" ? "simulation" : "success",
          source: result.mode,
        });
        notify(
          result.mode === "simulation"
            ? `${zone.name}: simulated only; no controller received this command.`
            : `${zone.name}: controller acknowledged ${result.actualState}.`,
          result.mode === "simulation" ? "simulation" : "success",
        );
        return true;
      } catch (error) {
        setZoneState(zoneId, {
          pending: false,
          ...(connection.mode === "simulation"
            ? {}
            : { enabled: zone.category === "safety" }),
          commandState: "error",
          lastCommand: error.message,
        });
        appendLog({ action: `${zone.name} · command failed · ${error.message}`, tone: "error", source: connection.mode });
        notify(`${zone.name}: ${error.message}`, "error");
        return false;
      }
    },
    [appendLog, connection, maintenanceActive, notify, setZoneState, zones],
  );

  const changeZoneSettings = useCallback((zoneId, patch) => {
    const zone = zones.find((item) => item.id === zoneId);
    if (!zone) return;
    setZoneState(zoneId, patch);
    appendLog({ action: `${zone.name} · settings edited · apply to send to controller`, tone: "info", source: connection.mode });
  }, [appendLog, connection.mode, setZoneState, zones]);

  const runMaster = useCallback(
    async (ids, state, label, options = {}) => {
      if (state === "OFF" && label.startsWith("ALL LIGHTS")) {
        const confirm = window.confirm("Switch all controllable lighting off? Emergency-exit lights stay ON and cannot be disabled by this command.");
        if (!confirm) return;
      }
      if (state === "OFF" && label.includes("RESIDENTIAL") && !window.confirm("Switch both residential lighting zones OFF?")) return;
      if (state === "OFF" && !label.startsWith("ALL LIGHTS") && !label.includes("RESIDENTIAL") && !window.confirm(`Switch ${label.replace("ALL ", "").replace(" OFF", "").toLowerCase()} OFF?`)) return;

      const selectedZones = zones.filter((zone) => ids.includes(zone.id) && !PROTECTED_ZONE_IDS.includes(zone.id));
      selectedZones.forEach((zone) => setZoneState(zone.id, { pending: true, commandState: "sending", lastCommand: "Master command sent · awaiting acknowledgement" }));
      appendLog({ action: `${label} · command sent (${selectedZones.length} protected-zone-aware outputs)`, tone: "info", source: connection.mode });

      try {
        const result = await sendMasterCommand(connection, selectedZones, state, options);
        const changedIds = [];
        for (let index = 0; index < result.allowedZones.length; index += 1) {
          const zone = result.allowedZones[index];
          const outcome = result.results[index];
          const acknowledged = outcome.mode === "simulation" || outcome.acknowledged === true;
          if (acknowledged) changedIds.push(zone.id);
          setZoneState(zone.id, {
            ...(acknowledged ? { enabled: outcome.actualState === "ON" } : {}),
            ...(!acknowledged ? { enabled: zone.category === "safety" } : {}),
            pending: false,
            commandState: outcome.mode === "simulation" ? "simulated" : acknowledged ? "acknowledged" : "error",
            lastCommand: outcome.status,
            updatedAt: new Date().toISOString(),
          });
        }
        const fullyAcknowledged = changedIds.length === result.allowedZones.length;
        notify(
          connection.mode === "simulation"
            ? `${label}: simulated only; emergency-exit lighting remains ON.`
            : `${label}: ${changedIds.length}/${result.allowedZones.length} controller replies received; ${result.allowedZones.length - changedIds.length} outputs still have unknown physical state.`,
          connection.mode === "simulation" ? "simulation" : fullyAcknowledged ? "success" : "warning",
        );
      } catch (error) {
        selectedZones.forEach((zone) =>
          setZoneState(zone.id, {
            pending: false,
            commandState: "error",
            lastCommand: error.message,
          }),
        );
        appendLog({ action: `${label} · failed · ${error.message}`, tone: "error", source: connection.mode });
        notify(`${label}: stopped after a controller error. ${error.message}`, "error");
      }
    },
    [appendLog, connection, notify, setZoneState, zones],
  );

  const scanDevices = useCallback(async () => {
    if (bluetoothBusy) return;
    setBluetoothBusy(true);
    setConnection((current) => ({ ...current, bluetoothError: "" }));
    setDevices([]);
    try {
      const results = await scanBluetoothDevices((device) =>
        setDevices((current) => current.some((item) => item.id === device.id) ? current : [...current, device]),
      );
      setDevices((current) => {
        const merged = [...current];
        results.forEach((device) => {
          if (!merged.some((item) => item.id === device.id)) merged.push(device);
        });
        return merged;
      });
      if (!results.length) notify("No paired Classic Bluetooth controllers were found. Pair your HC-05 in Android settings, then scan again.", "info");
      else notify(`${results.length} paired Bluetooth device${results.length === 1 ? "" : "s"} found.`, "info");
    } catch (error) {
      setConnection((current) => ({ ...current, bluetoothError: error.message }));
      notify(error.message, "error");
    } finally {
      setBluetoothBusy(false);
    }
  }, [bluetoothBusy, notify]);

  const scheduleAutoReconnect = useCallback((device) => {
    if (!device?.address) return;
    clearTimeout(autoReconnectTimer.current);
    if (autoReconnectAttempts.current >= AUTO_RECONNECT_LIMIT) {
      notify("Automatic Bluetooth reconnect stopped after three attempts. Check HC-05 power and wiring, then reconnect manually.", "error");
      return;
    }
    autoReconnectAttempts.current += 1;
    autoReconnectTimer.current = setTimeout(() => {
      autoReconnectTimer.current = null;
      connectionLossPending.current = false;
      connectDeviceRef.current?.(device, { automatic: true });
    }, AUTO_RECONNECT_DELAY_MS);
  }, [notify]);

  const connectDevice = useCallback(
    async (device, { automatic = false } = {}) => {
      if (!automatic) {
        clearTimeout(autoReconnectTimer.current);
        autoReconnectAttempts.current = 0;
      }
      setBluetoothBusy(true);
      setConnection((current) => ({ ...current, bluetoothError: "" }));
      try {
        const result = await connectBluetoothDevice(device, (event = {}) => {
          if (connectionLossPending.current || intentionalDisconnect.current) return;
          connectionLossPending.current = true;
          bluetoothDisconnectListener.current?.remove();
          bluetoothDisconnectListener.current = null;
          clearTimeout(connectionStableTimer.current);
          const reason = event.reason || "Bluetooth controller disconnected unexpectedly.";
          const disconnectedDevice = { ...device };
          setConnection((current) => ({
            ...current,
            ...(current.mode === "bluetooth" ? { mode: "simulation" } : {}),
            bluetoothDevice: null,
            bluetoothError: reason,
            relayWatchdogReady: false,
          }));
          returnToSafeSimulation();
          notify(`${reason} Lighting control returned to simulation.`, "error");
          scheduleAutoReconnect(disconnectedDevice);
        });
        if (result.controlReady !== true) {
          throw new Error("No verified Bluetooth lighting channel is available. The lighting controller remains in simulation mode.");
        }
        let relayWatchdogReady = false;
        let relayWatchdogError = "";
        try {
          await probeRelayWatchdog();
          relayWatchdogReady = true;
        } catch (error) {
          if (connectionLossPending.current) throw error;
          relayWatchdogError = error.message;
        }
        if (connectionLossPending.current) {
          throw new Error("Bluetooth disconnected before the relay watchdog could be verified.");
        }
        const connectedDevice = { ...device, name: result.name || device.name };
        setConnection((current) => ({
          ...current,
          mode: "bluetooth",
          bluetoothDevice: connectedDevice,
          bluetoothError: relayWatchdogError,
          relayWatchdogReady,
        }));
        bluetoothDisconnectListener.current?.remove();
        bluetoothDisconnectListener.current = result.listener || null;
        setLastBluetoothDevice(connectedDevice);
        writeStorage(BLUETOOTH_DEVICE_STORAGE_KEY, connectedDevice);
        connectionLossPending.current = false;
        clearTimeout(connectionStableTimer.current);
        connectionStableTimer.current = setTimeout(() => {
          autoReconnectAttempts.current = 0;
        }, 60000);
        markHardwareStatesUnknown();
        notify(
          relayWatchdogReady
            ? result.note || `Connected to ${result.name || device.name} over Bluetooth Classic SPP.`
            : `Connected to ${result.name || device.name}, but automatic relay control is disabled. ${relayWatchdogError}`,
          relayWatchdogReady ? "success" : "warning",
        );
      } catch (error) {
        const reconnectWasScheduled = connectionLossPending.current;
        connectionLossPending.current = false;
        setConnection((current) => ({ ...current, bluetoothError: error.message }));
        notify(`Bluetooth connection failed: ${error.message}`, "error");
        if (automatic && !reconnectWasScheduled) scheduleAutoReconnect(device);
      } finally {
        setBluetoothBusy(false);
      }
    },
    [markHardwareStatesUnknown, notify, returnToSafeSimulation, scheduleAutoReconnect],
  );

  useEffect(() => {
    connectDeviceRef.current = connectDevice;
  }, [connectDevice]);

  useEffect(() => {
    if (autoConnectStarted.current) return undefined;
    autoConnectStarted.current = true;
    if (!Capacitor.isNativePlatform() || Capacitor.getPlatform() !== "android") return undefined;

    let active = true;
    const autoConnect = async () => {
      if (lastBluetoothDevice?.address) {
        await connectDevice(lastBluetoothDevice, { automatic: true });
        return;
      }

      setBluetoothBusy(true);
      try {
        const pairedDevices = await scanBluetoothDevices();
        if (!active) return;
        setDevices(pairedDevices);
        const hc05 = pairedDevices.find((device) =>
          device.paired === true && /^(HC-05|HC05)$/i.test((device.name || "").trim()),
        );
        if (!hc05) {
          const message = "No paired HC-05 found. Pair it in Android Bluetooth settings, then reopen the app.";
          setConnection((current) => ({ ...current, bluetoothError: message }));
          notify(message, "warning");
          return;
        }
        await connectDevice(hc05, { automatic: true });
      } catch (error) {
        if (!active) return;
        setConnection((current) => ({ ...current, bluetoothError: error.message }));
        notify(`Automatic Bluetooth connection failed: ${error.message}`, "error");
      } finally {
        if (active) setBluetoothBusy(false);
      }
    };

    void autoConnect();
    return () => {
      active = false;
    };
  }, [connectDevice, lastBluetoothDevice, notify]);

  const disconnectDevice = useCallback(async () => {
    clearTimeout(autoReconnectTimer.current);
    clearTimeout(connectionStableTimer.current);
    connectionLossPending.current = false;
    intentionalDisconnect.current = true;
    try {
      if (connection.mode === "bluetooth") {
        await sendZone("additional", "OFF");
      }
      await disconnectBluetooth();
      await bluetoothDisconnectListener.current?.remove();
      bluetoothDisconnectListener.current = null;
      setConnection((current) => ({
        ...current,
        mode: current.mode === "bluetooth" ? "simulation" : current.mode,
        bluetoothDevice: null,
        bluetoothError: "",
        relayWatchdogReady: false,
      }));
      returnToSafeSimulation();
      notify("Bluetooth controller disconnected; lighting control is in simulation mode.", "simulation", "simulation");
    } catch (error) {
      notify(`Bluetooth disconnect failed: ${error.message}`, "error");
    } finally {
      intentionalDisconnect.current = false;
    }
  }, [connection.mode, connection.relayWatchdogReady, notify, returnToSafeSimulation, sendZone]);

  const activateMaintenance = useCallback(
    (inputPin) => {
      if (maintenancePin.length < 6) {
        notify("Set a 6-digit, session-only maintenance PIN in Settings first.", "warning");
        return false;
      }
      if (inputPin !== maintenancePin) {
        notify("Maintenance PIN does not match.", "error");
        return false;
      }
      setMaintenanceActive((active) => !active);
      notify(
        maintenanceActive ? "Restricted maintenance mode disabled." : "Restricted maintenance test mode enabled for this session only.",
        maintenanceActive ? "info" : "warning",
      );
      return true;
    },
    [maintenanceActive, maintenancePin, notify],
  );

  const applyScene = useCallback(
    async (scene) => {
      appendLog({ action: `Scene · ${scene.name} · apply requested`, tone: "info", source: connection.mode });
      const selectedIds = scene.zoneIds;
      const activeIds = new Set(zones.filter((zone) => selectedIds.includes(zone.id)).map((zone) => zone.id));
      const sceneCommands = [
        ...zones.filter((zone) => zone.category !== "safety" && activeIds.has(zone.id)).map((zone) => ({ id: zone.id, state: "ON" })),
        ...zones.filter((zone) => zone.category !== "safety" && !activeIds.has(zone.id)).map((zone) => ({ id: zone.id, state: "OFF" })),
      ];
      notify(`Applying ${scene.name}: ${sceneCommands.length} controller commands; safety lighting is protected.`, "info");
      for (const command of sceneCommands) {
        const zone = zones.find((item) => item.id === command.id);
        if (command.state === "ON") changeZoneSettings(zone.id, { color: scene.color, brightness: scene.brightness, effect: scene.effect });
        const sent = await sendZone(command.id, command.state);
        if (!sent) return false;
      }
      notify(`${scene.name}: all scene commands confirmed.`, "success");
      return true;
    },
    [appendLog, changeZoneSettings, connection.mode, notify, sendZone, zones],
  );

  const stats = useMemo(() => {
    const controllableZones = zones.filter((zone) => zone.category !== "safety");
    return {
      total: controllableZones.length,
      active: controllableZones.filter((zone) =>
        zone.enabled && (
          connection.mode === "simulation"
            ? zone.commandState === "simulated"
            : zone.commandState === "acknowledged"
        ),
      ).length,
      inactive: controllableZones.filter((zone) =>
        !zone.enabled || (connection.mode !== "simulation" && zone.commandState !== "acknowledged"),
      ).length,
      controllers: connection.mode === "simulation" ? 0 : 1,
      controllerTotal: new Set(controllableZones.map((zone) => zone.controller)).size,
    };
  }, [connection.mode, zones]);

  return {
    zones,
    logs,
    connection,
    devices,
    bluetoothBusy,
    stats,
    eventMessage,
    maintenanceActive,
    maintenancePin,
    setMaintenancePin,
    scanDevices,
    connectDevice,
    disconnectDevice,
    sendZone,
    changeZoneSettings,
    runMaster,
    applyScene,
    activateMaintenance,
    appendLog,
    resetZones,
    clearHistory,
    lastBluetoothDevice,
    notify,
    categories: { residential: RESIDENTIAL_ZONE_IDS, outdoor: OUTDOOR_ZONE_IDS, common: COMMON_ZONE_IDS },
  };
}
```

## `src/components/LightingZones.jsx`

```jsx
import { useMemo, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import {
  Building2,
  ChevronDown,
  CircleAlert,
  CloudSun,
  Coffee,
  Fan,
  House,
  PanelsTopLeft,
  Search,
  SlidersHorizontal,
  Sparkles,
} from "lucide-react";
import {
  COMMON_ZONE_IDS,
  OUTDOOR_ZONE_IDS,
  RESIDENTIAL_ZONE_IDS,
  ZONE_CATEGORIES,
} from "../data/lightingZones.js";
import LightingCard from "./LightingCard.jsx";
import { ActionButton, GlassCard, PageHeading, StatusPill } from "./UI.jsx";

const GROUPS = [
  { id: "residential", label: "Residential lighting", icon: House, caption: "Private homes · individually addressed" },
  { id: "common", label: "Common area", icon: PanelsTopLeft, caption: "Shared resident amenities and gathering spaces" },
  { id: "utility", label: "Building utility", icon: Fan, caption: "Service floors and back-of-house areas" },
  { id: "commercial", label: "Commercial", icon: Coffee, caption: "Ground-level retail and hospitality" },
  { id: "outdoor", label: "Outdoor lighting", icon: CloudSun, caption: "Elevated decks, balconies and gardens" },
  { id: "exterior", label: "Exterior architecture", icon: Building2, caption: "A composed, city-facing facade" },
  { id: "additional", label: "Additional area", icon: Sparkles, caption: "Configurable non-safety lighting zone" },
];

export default function LightingZones({
  zones,
  connection,
  animation,
  maintenanceActive,
  onCommand,
  onAllOn,
  onAllOff,
  onGroupControl,
}) {
  const [filter, setFilter] = useState("all");
  const [query, setQuery] = useState("");
  const [collapsed, setCollapsed] = useState({});
  const visibleZones = useMemo(() => zones.filter((zone) => zone.category !== "safety"), [zones]);
  const filtered = useMemo(() => visibleZones.filter((zone) => (
    (filter === "all" || zone.category === filter)
    && (`${zone.name} ${ZONE_CATEGORIES[zone.category]}`.toLowerCase().includes(query.toLowerCase()))
  )), [filter, query, visibleZones]);

  const tabs = [
    { id: "all", label: "All zones", count: visibleZones.length },
    ...Object.entries(ZONE_CATEGORIES).filter(([id]) => visibleZones.some((zone) => zone.category === id)).map(([id, label]) => ({
      id,
      label,
      count: visibleZones.filter((zone) => zone.category === id).length,
    })),
  ];
  const failedZoneCount = visibleZones.filter((zone) => zone.commandState === "error").length;

  return (
    <div className="page-stack">
      <PageHeading
        eyebrow="CONTROL THE ATMOSPHERE"
        title="Lighting zones"
        description={`${visibleZones.length} individually addressable zones. Every command carries a clear hardware or simulator status.`}
        icon={SlidersHorizontal}
        trailing={
          <div className="zone-summary">
            <StatusPill tone="success">{visibleZones.filter((zone) =>
              zone.enabled && (connection.mode === "simulation"
                ? zone.commandState === "simulated"
                : zone.commandState === "acknowledged"),
            ).length} ON</StatusPill>
            <StatusPill tone={connection.mode === "simulation" ? "simulation" : "success"}>
              {connection.mode === "simulation" ? "SIMULATOR" : "CONTROLLER LINKED"}
            </StatusPill>
          </div>
        }
      />

      <GlassCard className="zones-master-bar">
        <div className="zones-master-bar__intro">
          <span className="zones-master-bar__icon"><Sparkles size={16} /></span>
          <span><strong>MASTER CONTROL</strong><small>Emergency lighting is excluded from these controls.</small></span>
        </div>
        <div className="zones-master-bar__actions">
          <div className="master-groups">
            <ActionButton icon={Sparkles} onClick={onAllOn}>ALL LIGHTS ON</ActionButton>
            <ActionButton variant="danger-outline" onClick={onAllOff}>ALL LIGHTS OFF</ActionButton>
            <ActionButton variant="outline" onClick={() => onGroupControl([...OUTDOOR_ZONE_IDS], "ON", "ALL OUTDOOR ON")}>OUTDOOR ON</ActionButton>
            <ActionButton variant="subtle" onClick={() => onGroupControl([...OUTDOOR_ZONE_IDS], "OFF", "ALL OUTDOOR OFF")}>OUTDOOR OFF</ActionButton>
            <ActionButton variant="outline" onClick={() => onGroupControl([...RESIDENTIAL_ZONE_IDS], "ON", "ALL RESIDENTIAL ON")}>RESIDENTIAL ON</ActionButton>
            <ActionButton variant="subtle" onClick={() => onGroupControl([...RESIDENTIAL_ZONE_IDS], "OFF", "ALL RESIDENTIAL OFF")}>RESIDENTIAL OFF</ActionButton>
            <ActionButton variant="outline" onClick={() => onGroupControl([...COMMON_ZONE_IDS], "ON", "ALL COMMON AREAS ON")}>COMMON AREAS ON</ActionButton>
            <ActionButton variant="subtle" onClick={() => onGroupControl([...COMMON_ZONE_IDS], "OFF", "ALL COMMON AREAS OFF")}>COMMON AREAS OFF</ActionButton>
          </div>
        </div>
      </GlassCard>

      {failedZoneCount > 0 && (
        <div className="controller-state-banner" role="alert">
          <CircleAlert size={17} />
          <div className="controller-state-banner__copy">
            <strong>Controller response unavailable</strong>
            <span>
              {failedZoneCount} {failedZoneCount === 1 ? "zone command did" : "zone commands did"} not receive a controller response.
              Physical light state is unknown; the app has not assumed the requested state.
            </span>
          </div>
        </div>
      )}

      <div className="zones-filters">
        <div className="zones-filters__chips" role="tablist" aria-label="Filter lighting zones">
          {tabs.map((tab) => (
            <button
              key={tab.id}
              className={`filter-chip ${filter === tab.id ? "filter-chip--active" : ""}`}
              role="tab"
              aria-selected={filter === tab.id}
              onClick={() => setFilter(tab.id)}
            >
              {tab.label}<span>{tab.count}</span>
            </button>
          ))}
        </div>
        <label className="zone-search">
          <Search size={15} />
          <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Find a zone…" />
        </label>
      </div>

      <div className="zone-groups">
        {GROUPS.map((group) => {
          const items = filtered.filter((zone) => zone.category === group.id);
          if (!items.length) return null;
          const Icon = group.icon;
          const isCollapsed = collapsed[group.id];
          return (
            <section className="zone-group" key={group.id}>
              <button
                className="zone-group__heading"
                aria-expanded={!isCollapsed}
                onClick={() => setCollapsed((state) => ({ ...state, [group.id]: !state[group.id] }))}
              >
                <span className={`zone-group__icon zone-group__icon--${group.id}`}><Icon size={16} /></span>
                <span className="zone-group__name"><strong>{group.label}</strong><small>{group.caption}</small></span>
                <span className="zone-group__count">{items.length} {items.length === 1 ? "ZONE" : "ZONES"}</span>
                <ChevronDown size={16} className={isCollapsed ? "rotate-180" : ""} />
              </button>
              <AnimatePresence initial={false}>
                {!isCollapsed && (
                  <motion.div
                    className="zone-grid"
                    initial={{ opacity: 0, height: 0 }}
                    animate={{ opacity: 1, height: "auto" }}
                    exit={{ opacity: 0, height: 0 }}
                    transition={{ duration: 0.23 }}
                  >
                    {items.map((zone, index) => (
                      <motion.div
                        key={zone.id}
                        initial={{ opacity: 0, y: 12 }}
                        animate={{ opacity: 1, y: 0 }}
                        transition={{ delay: Math.min(index * 0.045, 0.18), duration: 0.3 }}
                      >
                        <LightingCard
                          zone={zone}
                          connection={connection}
                          animation={animation}
                          maintenanceActive={maintenanceActive}
                          onCommand={onCommand}
                        />
                      </motion.div>
                    ))}
                  </motion.div>
                )}
              </AnimatePresence>
            </section>
          );
        })}
      </div>
      {filtered.length === 0 && (
        <GlassCard className="zones-no-results">
          <Search size={17} /> No zones match “{query}”.
          <button onClick={() => { setQuery(""); setFilter("all"); }}>Clear filters</button>
        </GlassCard>
      )}
    </div>
  );
}
```

## `src/components/LightingCard.jsx`

```jsx
import { useState } from "react";
import {
  Armchair,
  BellRing,
  Building,
  Coffee,
  Fan,
  Flower2,
  House,
  Lightbulb,
  PanelsTopLeft,
  Shield,
  Sparkles,
} from "lucide-react";
import { ZONE_IMAGES } from "../data/lightingZones.js";
import { ActionButton, ControllerTag, StatusPill } from "./UI.jsx";

const AREA_META = {
  "3 BHK": { icon: House, summary: "Private residence", background: "#ad8168", image: "/assets/mahindra-tower-day.jpg", positioning: "center center" },
  "4 BHK": { icon: House, summary: "Private residence", background: "#806a7b", image: "/assets/mahindra-tower-day.jpg", positioning: "52% center" },
  Clubhouse: { icon: Armchair, summary: "Resident amenities", background: "#548c8f" },
  "Refuse / Emergency Exit": { icon: Shield, summary: "Life safety · protected", background: "#9e5855" },
  "Service Floor": { icon: Fan, summary: "Back-of-house", background: "#707b88" },
  Cafe: { icon: Coffee, summary: "Ground-floor retail", background: "#986d4f" },
  Podium: { icon: PanelsTopLeft, summary: "Elevated amenity deck", background: "#718676" },
  Swimming Pool: { icon: House, summary: "Private outdoor space", background: "#a77867" },
  Landscape: { icon: Flower2, summary: "Garden & arrival", background: "#688675" },
  "Animation": { icon: Building, summary: "Architectural lighting", background: "#627080" },
  "Additional Area": { icon: Sparkles, summary: "Additional non-safety lighting", background: "#7c7392" },
};

function ZoneArtwork({ zone, isOn, animation }) {
  const [imageFailed, setImageFailed] = useState(false);
  const meta = AREA_META[zone.name] || AREA_META["Additional Area"];
  const Icon = meta.icon || Lightbulb;
  const image = ZONE_IMAGES[zone.image];

  return (
    <div
      className={`zone-artwork ${isOn ? "zone-artwork--on" : ""} ${zone.category === "safety" ? "zone-artwork--safety" : ""}`}
      style={{ "--zone-tint": meta.background, "--zone-color": zone.color }}
    >
      {image && !imageFailed && (
        <img
          src={image}
          alt={`${zone.name} · Mahindra Beacon project render`}
          loading="lazy"
          onError={() => setImageFailed(true)}
        />
      )}
      <div className="zone-artwork__overlay" />
      {!image || imageFailed ? (
        <div className="zone-artwork__fallback">
          {zone.name === "Refuse / Emergency Exit" ? (
            <BellRing size={32} strokeWidth={1.15} />
          ) : zone.category === "outdoor" ? (
            <Flower2 size={37} strokeWidth={1.1} />
          ) : (
            <Icon size={34} strokeWidth={1.1} />
          )}
        </div>
      ) : (
        <span className="zone-artwork__icon"><Icon size={16} strokeWidth={1.55} /></span>
      )}
      {isOn && <span className="zone-artwork__glow" />}
      {animation?.playing && ["facade", "balcony", "podium", "landscape"].includes(zone.id) && (
        <span className={`zone-artwork__animation effect-${animation.effect}`} />
      )}
      {zone.category === "safety" && <span className="zone-artwork__safety-badge"><Shield size={12} /> 24/7 LIFE SAFETY</span>}
    </div>
  );
}

export default function LightingCard({
  zone,
  connection,
  animation,
  maintenanceActive,
  onCommand,
}) {
  const isOn = zone.enabled;
  const isSafety = zone.category === "safety";
  const unknown = connection.mode !== "simulation" && !["acknowledged", "sending"].includes(zone.commandState);
  const displayOn = connection.mode === "simulation" ? isOn : zone.commandState === "acknowledged" && isOn;
  const signalTone = zone.commandState === "error" ? "danger" : displayOn ? "success" : "muted";

  return (
    <article
      className={`zone-card ${displayOn ? "zone-card--active" : ""} ${isSafety ? "zone-card--safety" : ""}`}
      style={{ "--zone-color": zone.color }}
      aria-label={`${zone.name} lighting controls`}
    >
      <ZoneArtwork zone={zone} isOn={displayOn} animation={animation} />
      <div className="zone-card__body">
        <div className="zone-card__topline">
          <span className="zone-card__category">{AREA_META[zone.name].summary}</span>
          <ControllerTag zone={zone} connection={connection} />
        </div>
        <div className="zone-card__title-row">
          <h3>{zone.name}</h3>
          <span className={`zone-indicator ${displayOn ? "zone-indicator--on" : ""} ${zone.pending ? "zone-indicator--pending" : ""}`} />
        </div>
        {zone.commandState !== "error" && (
          <div className="zone-card__status-row">
            <StatusPill
              tone={signalTone}
              pulse={Boolean(zone.pending) || Boolean(isOn && connection.mode === "bluetooth")}
            >
              {zone.pending
                ? "SENDING"
                : connection.mode !== "simulation"
                  ? zone.commandState === "acknowledged"
                    ? isOn ? "ACKNOWLEDGED · ON" : "ACKNOWLEDGED · OFF"
                    : isSafety ? "PROTECTED · STATE UNKNOWN" : "HARDWARE STATE UNKNOWN"
                  : isOn ? "SIMULATED ON" : "SIMULATED OFF"}
            </StatusPill>
            <span className="zone-card__mode">
              {zone.pending ? "COMMAND IN PROGRESS" :
                zone.commandState === "acknowledged" ? "CONTROLLER REPLIED" :
                  connection.mode === "simulation" ? "VISUAL PREVIEW" :
                    isSafety ? "PROTECTED" : "NOT QUERIED"}
            </span>
          </div>
        )}

        <div className="zone-controls">
          <ActionButton
            variant={displayOn ? "active" : "subtle"}
            disabled={Boolean(zone.pending) || (displayOn && !unknown)}
            onClick={() => onCommand(zone.id, "ON")}
          >
            ON
          </ActionButton>
          {isSafety ? (
            <ActionButton
              variant="danger-outline"
              disabled={!maintenanceActive || Boolean(zone.pending) || !isOn}
              onClick={() => {
                if (window.confirm("Run an emergency-exit indicator test? This sends an OFF command only in the restricted maintenance session. Restore the light immediately after the test.")) {
                  onCommand(zone.id, "OFF", { maintenanceAuthorized: true });
                }
              }}
            >
              TEST OFF
            </ActionButton>
          ) : (
            <ActionButton
              variant="outline"
              disabled={Boolean(zone.pending) || (!displayOn && !unknown)}
              onClick={() => onCommand(zone.id, "OFF")}
            >
              OFF
            </ActionButton>
          )}
        </div>

        {isSafety && (
          <div className="zone-safety-note">
            <Shield size={12} />
            Always excluded from all-off scenes and master shut-down.
            {!maintenanceActive && <span>Restricted test mode required.</span>}
          </div>
        )}
        {zone.commandState !== "error" && (
          <div className="zone-card__last-command">
            <span className="zone-card__last-dot" />
            <span>{zone.lastCommand}</span>
          </div>
        )}
      </div>
    </article>
  );
}

export { AREA_META };
```

## `src/pages/Connections.jsx`

```jsx
import {
  Bluetooth,
  Check,
  CircleAlert,
  ChevronRight,
  CircleHelp,
  ExternalLink,
  Info,
  Radio,
  RefreshCcw,
  ShieldCheck,
  Signal,
  Smartphone,
} from "lucide-react";
import {
  ActionButton,
  GlassCard,
  PageHeading,
  StatusPill,
} from "../components/UI.jsx";

export default function Connections({
  connection,
  devices,
  bluetoothBusy,
  scanDevices,
  connectDevice,
  disconnectDevice,
  reconnectDevice,
  lastBluetoothDevice,
}) {
  const isBluetoothActive = connection.mode === "bluetooth";
  return (
    <div className="page-stack connections-page">
      <PageHeading
        eyebrow="YOUR HARDWARE. ON ITS OWN TERMS."
        title="Connections"
        description="On Android, the app reconnects to a paired HC-05 automatically. Pair it once in Android Bluetooth settings."
        icon={Signal}
        trailing={
          <StatusPill tone={connection.mode === "simulation" ? "simulation" : "success"} pulse={connection.mode !== "simulation"}>
            {connection.mode === "simulation" ? "NO HARDWARE LINK · SIMULATOR" : "CONTROLLER CONNECTED"}
          </StatusPill>
        }
      />

      {connection.mode === "simulation" && (
        <div className="connection-simulation-banner">
          <span><Info size={15} /></span>
          <span><strong>VISUAL SIMULATION IS ACTIVE.</strong> Commands only change this device's illustrated scene. A real, verified controller response is required before the application shows a hardware-confirmed state.</span>
          <StatusPill tone="simulation">NOT A LIVE BUILDING</StatusPill>
        </div>
      )}

      <div className="connections-grid connections-grid--single">
        <GlassCard className={`connection-method-card ${isBluetoothActive ? "connection-method-card--connected" : ""}`}>
          <div className="connection-card__topline">
            <span className="connection-card__icon connection-card__icon--bt"><Bluetooth size={20} /></span>
            <span className="connection-card__protocol">NATIVE ANDROID · SPP</span>
            <StatusPill tone={isBluetoothActive ? "success" : "muted"} pulse={isBluetoothActive}>
              {isBluetoothActive ? "PAIRED & CONNECTED" : "NOT CONNECTED"}
            </StatusPill>
          </div>
          <h2>Bluetooth Classic</h2>
          <p>Pair your HC-05 or HC-06 in Android Bluetooth settings, then connect to its RFCOMM Serial Port Profile.</p>
          {isBluetoothActive ? (
            <div className="connection-connected-device">
              <span className="connection-connected-device__mark"><Bluetooth size={17} /></span>
              <span><strong>{connection.bluetoothDevice?.name || "Paired SPP controller"}</strong><small>{connection.bluetoothDevice?.address || "BLUETOOTH CLASSIC · RFCOMM"}</small></span>
              <Check size={16} className="connection-connected-device__check" />
            </div>
          ) : (
            <div className="bluetooth-discovery-panel">
              {lastBluetoothDevice && (
                <ActionButton
                  icon={RefreshCcw}
                  variant="outline"
                  disabled={bluetoothBusy}
                  onClick={reconnectDevice}
                >
                  {bluetoothBusy ? "RECONNECTING…" : `RECONNECT · ${lastBluetoothDevice.name}`}
                </ActionButton>
              )}
              <button className="discovery-button" onClick={scanDevices} disabled={bluetoothBusy}>
                <span className={bluetoothBusy ? "discovery-spinner" : ""}>{bluetoothBusy ? <RefreshCcw size={15} /> : <Radio size={15} />}</span>
                {bluetoothBusy ? "SEARCHING FOR CONTROLLERS…" : "SCAN FOR CLASSIC DEVICES"}
                {!bluetoothBusy && <ChevronRight size={14} />}
              </button>
              {devices.length > 0 && (
                <div className="bluetooth-device-list">
                  <span>DISCOVERED OR PAIRED DEVICES</span>
                  {devices.map((device) => (
                    <div className="bluetooth-device-row" key={device.id || device.address}>
                      <span className="bluetooth-device-row__icon"><Bluetooth size={14} /></span>
                      <span className="bluetooth-device-row__name"><strong>{device.name || "Unnamed device"}</strong><small>{device.address || device.id}</small></span>
                      <button
                        disabled={bluetoothBusy || !device.address || device.paired !== true}
                        title={device.paired === true ? undefined : "Pair this device in Android Bluetooth settings first"}
                        onClick={() => connectDevice(device)}
                        aria-label={`Connect to ${device.name}`}
                      >
                        {device.paired === true ? <>CONNECT <ChevronRight size={12} /></> : "PAIR IN SETTINGS"}
                      </button>
                    </div>
                  ))}
                </div>
              )}
              {connection.bluetoothError && (
                <div className="connection-error"><CircleAlert size={13} /><span>{connection.bluetoothError}</span></div>
              )}
            </div>
          )}
          {isBluetoothActive && (
            <button className="connection-disconnect" onClick={disconnectDevice}>DISCONNECT CLASSIC SPP <ExternalLink size={13} /></button>
          )}
          <div className="connection-note">
            <Smartphone size={13} />
            <span>
              <strong>HC-05 uses Bluetooth Classic, not BLE.</strong>
              Browser Web Bluetooth cannot connect to SPP.               The Capacitor Android app provides a native RFCOMM bridge and scans for a paired HC-05 on startup.
            </span>
          </div>
          <div className="connection-card__capabilities">
            <span><Check size={12} /> Device discovery</span>
            <span><Check size={12} /> Paired SPP connection</span>
            <span><Check size={12} /> Per-zone command status</span>
            <span><Check size={12} /> Relays D2–D9 & D12–D13 · HC-05 D10/D11</span>
          </div>
        </GlassCard>

      </div>

      <div className="connections-footer">
        <span><ShieldCheck size={14} /> Privacy first</span>
        <span>Zone state follows the controller's response.</span>
        <span><CircleHelp size={13} /> Pair HC-05 in Android settings once; manual scan remains available.</span>
      </div>
    </div>
  );
}
```

## `android/app/src/main/java/com/mahindra/beaconhill/MainActivity.java`

```java
package com.mahindra.beaconhill;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        registerPlugin(ClassicBluetoothPlugin.class);
        super.onCreate(savedInstanceState);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        com.getcapacitor.Bridge bridge = getBridge();
        if (bridge != null && bridge.getWebView() != null && bridge.getWebView().canGoBack()) {
            bridge.getWebView().goBack();
            return;
        }
        super.onBackPressed();
    }
}
```

## `android/app/src/main/java/com/mahindra/beaconhill/ClassicBluetoothPlugin.java`

```java
package com.mahindra.beaconhill;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@CapacitorPlugin(
    name = "ClassicBluetooth",
    permissions = {
        @Permission(
            alias = "bluetooth",
            strings = { Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT }
        ),
        @Permission(alias = "location", strings = { Manifest.permission.ACCESS_FINE_LOCATION })
    }
)
public final class ClassicBluetoothPlugin extends Plugin {
    private static final UUID SPP_UUID =
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final long SCAN_DURATION_MS = 12_000;
    private static final long CONNECT_TIMEOUT_MS = 12_000;
    private static final long COMMAND_TIMEOUT_MS = 2_000;
    private static final int MAX_RESPONSE_BYTES = 8_192;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object connectionLock = new Object();
    private final List<JSObject> scannedDevices = new ArrayList<>();
    private volatile BluetoothSocket socket;
    private volatile PluginCall scanCall;
    private volatile BroadcastReceiver scanReceiver;
    private volatile BroadcastReceiver disconnectReceiver;
    private final Runnable finishScanRunnable = this::finishScan;
    private final AtomicBoolean commandPending = new AtomicBoolean(false);
    private final AtomicBoolean heartbeatPending = new AtomicBoolean(false);
    private final AtomicInteger commandSequence = new AtomicInteger(0);

    private BluetoothAdapter adapter() {
        BluetoothManager manager =
            (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        return manager == null ? null : manager.getAdapter();
    }

    private boolean hasRuntimePermission(String alias) {
        return getPermissionState(alias) == PermissionState.GRANTED;
    }

    @PluginMethod
    public void scanDevices(PluginCall call) {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? "bluetooth" : "location";
        if (!hasRuntimePermission(permission)) {
            requestPermissionForAlias(permission, call, "scanDevicesPermissionCallback");
            return;
        }
        startScan(call);
    }

    @PermissionCallback
    private void scanDevicesPermissionCallback(PluginCall call) {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? "bluetooth" : "location";
        if (!hasRuntimePermission(permission)) {
            call.reject("Bluetooth scanning permission was not granted.");
            return;
        }
        startScan(call);
    }

    private synchronized void startScan(PluginCall call) {
        BluetoothAdapter bluetooth = adapter();
        if (bluetooth == null) {
            call.reject("This Android device does not support Bluetooth Classic.");
            return;
        }
        if (!bluetooth.isEnabled()) {
            call.reject("Turn on Bluetooth in Android settings, then scan again.");
            return;
        }
        if (scanCall != null) {
            finishScan();
        }

        scannedDevices.clear();
        try {
            Set<BluetoothDevice> paired = bluetooth.getBondedDevices();
            if (paired != null) {
                for (BluetoothDevice device : paired) {
                    addScannedDevice(device);
                }
            }
        } catch (SecurityException exception) {
            call.reject("Android denied access to paired Bluetooth devices.", exception);
            return;
        }

        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        scanReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
                    return;
                }
                BluetoothDevice device;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    device = intent.getParcelableExtra(
                        BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice.class
                    );
                } else {
                    device = (BluetoothDevice) intent.getParcelableExtra(
                        BluetoothDevice.EXTRA_DEVICE
                    );
                }
                if (device != null) {
                    JSObject discovered = addScannedDevice(device);
                    if (discovered != null) {
                        notifyListeners("deviceFound", discovered);
                    }
                }
            }
        };

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getContext().registerReceiver(scanReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                getContext().registerReceiver(scanReceiver, filter);
            }
            scanCall = call;
            call.setKeepAlive(true);
            if (!bluetooth.startDiscovery()) {
                finishScan();
                return;
            }
            mainHandler.postDelayed(finishScanRunnable, SCAN_DURATION_MS);
        } catch (SecurityException exception) {
            unregisterScanReceiver();
            scanCall = null;
            call.reject("Android could not start a Bluetooth device scan.", exception);
        } catch (RuntimeException exception) {
            unregisterScanReceiver();
            scanCall = null;
            call.reject("Bluetooth scanning failed: " + exception.getMessage(), exception);
        }
    }

    private JSObject addScannedDevice(BluetoothDevice device) {
        try {
            String address = device.getAddress();
            if (address == null) {
                return null;
            }
            synchronized (scannedDevices) {
                for (JSObject existing : scannedDevices) {
                    if (address.equals(existing.getString("address"))) {
                        return null;
                    }
                }
                JSObject result = new JSObject();
                result.put("id", address);
                result.put("address", address);
                result.put("name", device.getName() == null ? "Unnamed Bluetooth device" : device.getName());
                result.put("paired", device.getBondState() == BluetoothDevice.BOND_BONDED);
                scannedDevices.add(result);
                return result;
            }
        } catch (SecurityException exception) {
            throw exception;
        }
    }

    private synchronized void finishScan() {
        mainHandler.removeCallbacks(finishScanRunnable);
        BluetoothAdapter bluetooth = adapter();
        try {
            if (bluetooth != null && bluetooth.isDiscovering()) {
                bluetooth.cancelDiscovery();
            }
        } catch (SecurityException ignored) {
            // Permission may have been revoked while the scan was running.
        }
        unregisterScanReceiver();
        PluginCall activeScan = scanCall;
        scanCall = null;
        if (activeScan != null) {
            JSArray devices = new JSArray();
            synchronized (scannedDevices) {
                for (JSObject device : scannedDevices) {
                    devices.put(device);
                }
            }
            JSObject result = new JSObject();
            result.put("devices", devices);
            activeScan.setKeepAlive(false);
            activeScan.resolve(result);
        }
    }

    private void unregisterScanReceiver() {
        BroadcastReceiver receiver = scanReceiver;
        scanReceiver = null;
        if (receiver != null) {
            try {
                getContext().unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
                // The scan was already stopped.
            }
        }
    }

    @PluginMethod
    public void connect(PluginCall call) {
        if (!hasRuntimePermission("bluetooth")) {
            requestPermissionForAlias("bluetooth", call, "connectPermissionCallback");
            return;
        }
        connectPairedDevice(call);
    }

    @PermissionCallback
    private void connectPermissionCallback(PluginCall call) {
        if (!hasRuntimePermission("bluetooth")) {
            call.reject("Bluetooth connection permission was not granted.");
            return;
        }
        connectPairedDevice(call);
    }

    private void connectPairedDevice(PluginCall call) {
        String address = call.getString("address");
        BluetoothAdapter bluetooth = adapter();
        if (bluetooth == null) {
            call.reject("This Android device does not support Bluetooth Classic.");
            return;
        }
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            call.reject("Choose a paired Bluetooth Classic device with a valid address.");
            return;
        }

        call.setKeepAlive(true);
        AtomicBoolean finished = new AtomicBoolean(false);
        BluetoothSocket[] pendingSocket = new BluetoothSocket[1];
        Runnable timeout = () -> {
            if (finished.compareAndSet(false, true)) {
                closeQuietly(pendingSocket[0]);
                call.setKeepAlive(false);
                call.reject("The Bluetooth controller did not connect within 12 seconds.");
            }
        };
        mainHandler.postDelayed(timeout, CONNECT_TIMEOUT_MS);

        io.execute(() -> {
            try {
                BluetoothDevice device = bluetooth.getRemoteDevice(address);
                if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                    throw new IOException(
                        "Pair this device in Android Bluetooth settings before connecting."
                    );
                }
                BluetoothSocket nextSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                pendingSocket[0] = nextSocket;
                bluetooth.cancelDiscovery();
                nextSocket.connect();
                if (finished.get()) {
                    closeQuietly(nextSocket);
                    return;
                }
                synchronized (connectionLock) {
                    closeConnectionLocked();
                    socket = nextSocket;
                    registerDisconnectReceiver(address);
                }
                JSObject result = new JSObject();
                result.put("name", device.getName() == null ? "Bluetooth Classic controller" : device.getName());
                result.put("address", address);
                result.put("controlReady", true);
                mainHandler.post(() -> {
                    if (finished.compareAndSet(false, true)) {
                        mainHandler.removeCallbacks(timeout);
                        call.setKeepAlive(false);
                        call.resolve(result);
                    }
                });
            } catch (Exception exception) {
                closeQuietly(pendingSocket[0]);
                mainHandler.post(() -> {
                    if (finished.compareAndSet(false, true)) {
                        mainHandler.removeCallbacks(timeout);
                        call.setKeepAlive(false);
                        call.reject(
                            "Could not connect over Bluetooth Classic: " + exception.getMessage(),
                            exception
                        );
                    }
                });
            }
        });
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        synchronized (connectionLock) {
            closeConnectionLocked();
        }
        JSObject result = new JSObject();
        result.put("disconnected", true);
        call.resolve(result);
    }

    @PluginMethod
    public void sendCommand(PluginCall call) {
        String zoneId = call.getString("zoneId");
        String state = call.getString("state");
        if (zoneId == null || zoneId.trim().isEmpty() || !("ON".equals(state) || "OFF".equals(state))) {
            call.reject("The Bluetooth command requires a zone ID and an ON or OFF state.");
            return;
        }
        if ("emergency".equals(zoneId)) {
            call.reject("Life-safety lighting is not controlled by the general-purpose Bluetooth relay system.");
            return;
        }
        JSObject command = new JSObject();
        command.put("zoneId", zoneId);
        command.put("state", state);
        command.put("brightness", call.getInt("brightness", 72));
        command.put("color", call.getString("color", "#eac28a"));
        command.put("effect", call.getString("effect", "steady"));
        executeCommand(call, command);
    }

    @PluginMethod
    public void sendMasterCommand(PluginCall call) {
        String state = call.getString("state");
        JSArray zoneIds = call.getArray("zoneIds");
        if (!("ON".equals(state) || "OFF".equals(state)) || zoneIds == null || zoneIds.length() == 0) {
            call.reject("The master command requires one or more zone IDs and an ON or OFF state.");
            return;
        }

        JSObject command = new JSObject();
        command.put("command", "master");
        command.put("state", state);
        command.put("zoneIds", zoneIds);
        command.put("maintenanceAuthorized", false);
        for (int index = 0; index < zoneIds.length(); index++) {
            try {
                if ("emergency".equals(zoneIds.getString(index))) {
                    call.reject("Life-safety lighting is excluded from master relay commands.");
                    return;
                }
            } catch (Exception exception) {
                call.reject("The master command contains an invalid zone ID.", exception);
                return;
            }
        }
        executeCommand(call, command);
    }

    @PluginMethod
    public void probeWatchdog(PluginCall call) {
        JSObject command = new JSObject();
        command.put("command", "watchdogProbe");
        executeCommand(call, command);
    }

    @PluginMethod
    public void sendHeartbeat(PluginCall call) {
        BluetoothSocket activeSocket = socket;
        if (activeSocket == null || !activeSocket.isConnected()) {
            call.reject("The Bluetooth Classic controller is not connected.");
            return;
        }
        if (!heartbeatPending.compareAndSet(false, true)) {
            JSObject result = new JSObject();
            result.put("sent", false);
            call.resolve(result);
            return;
        }

        call.setKeepAlive(true);
        io.execute(() -> {
            try {
                if (socket != activeSocket || !activeSocket.isConnected()) {
                    throw new IOException("The Bluetooth connection is no longer active.");
                }
                OutputStream output = activeSocket.getOutputStream();
                output.write("{\"command\":\"heartbeat\"}\n".getBytes(StandardCharsets.UTF_8));
                output.flush();
                mainHandler.post(() -> {
                    heartbeatPending.set(false);
                    call.setKeepAlive(false);
                    JSObject result = new JSObject();
                    result.put("sent", true);
                    call.resolve(result);
                });
            } catch (Exception exception) {
                if (exception instanceof IOException && socket == activeSocket) {
                    reportConnectionLost("Bluetooth heartbeat failed: " + exception.getMessage());
                }
                mainHandler.post(() -> {
                    heartbeatPending.set(false);
                    call.setKeepAlive(false);
                    call.reject("Could not send the relay heartbeat: " + exception.getMessage(), exception);
                });
            }
        });
    }

    private void executeCommand(PluginCall call, JSObject command) {
        BluetoothSocket activeSocket = socket;
        if (activeSocket == null || !activeSocket.isConnected()) {
            reportConnectionLost(
                "The Bluetooth connection is no longer active. Reconnect to the HC-05 before sending commands."
            );
            call.reject("The Bluetooth Classic controller is not connected.");
            return;
        }
        if (!commandPending.compareAndSet(false, true)) {
            call.reject("Another Bluetooth lighting command is awaiting acknowledgement.");
            return;
        }

        int commandId = commandSequence.updateAndGet(current -> current == Integer.MAX_VALUE ? 1 : current + 1);
        command.put("commandId", commandId);
        call.setKeepAlive(true);
        AtomicBoolean finished = new AtomicBoolean(false);
        io.execute(() -> {
            try {
                OutputStream output = activeSocket.getOutputStream();
                output.write((command.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
                JSObject acknowledgement = readMatchingResponse(activeSocket, commandId, command);
                finishCommandCall(call, finished, acknowledgement, null);
            } catch (CommandTimeoutException exception) {
                finishCommandCall(call, finished, null, exception.getMessage());
            } catch (Exception exception) {
                if (exception instanceof IOException && socket == activeSocket) {
                    reportConnectionLost("Bluetooth transport failed: " + exception.getMessage());
                }
                finishCommandCall(call, finished, null, exception.getMessage());
            }
        });
    }

    private JSObject readMatchingResponse(
        BluetoothSocket activeSocket,
        int commandId,
        JSObject command
    )
        throws IOException, InterruptedException {
        InputStream input = activeSocket.getInputStream();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean overflow = false;
        long deadline = SystemClock.elapsedRealtime() + COMMAND_TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (socket != activeSocket || !activeSocket.isConnected()) {
                throw new IOException("The HC-05 socket closed while waiting for the controller.");
            }
            if (input.available() == 0) {
                Thread.sleep(5);
                continue;
            }
            int next = input.read();
            if (next < 0) {
                throw new IOException("The controller closed the Bluetooth connection.");
            }
            if (next == '\n') {
                if (!overflow && bytes.size() > 0) {
                    try {
                        JSObject response = new JSObject(
                            bytes.toString(StandardCharsets.UTF_8.name()).trim()
                        );
                        if (response.has("commandId")) {
                            if (response.getInt("commandId") == commandId) {
                                return response;
                            }
                        } else if (commandMatchesLegacyReply(command, response)) {
                            return response;
                        }
                    } catch (Exception ignored) {
                        // Ignore malformed or stale replies and wait for this command's ID.
                    }
                }
                bytes.reset();
                overflow = false;
            } else if (next != '\r') {
                if (bytes.size() < MAX_RESPONSE_BYTES) {
                    bytes.write(next);
                } else {
                    overflow = true;
                }
            }
        }
        throw new CommandTimeoutException(
            "The controller did not respond within two seconds; Bluetooth remains connected."
        );
    }

    private static boolean commandMatchesLegacyReply(JSObject command, JSObject response) {
        try {
            String state = command.getString("state");
            if (state == null || !state.equals(response.getString("state"))) {
                return false;
            }
            if ("master".equals(command.getString("command"))) {
                return "invalid_command".equals(response.getString("error"))
                    && "".equals(response.getString("zoneId"));
            }
            String zoneId = command.getString("zoneId");
            return zoneId != null && zoneId.equals(response.getString("zoneId"));
        } catch (Exception exception) {
            return false;
        }
    }

    private void finishCommandCall(
        PluginCall call,
        AtomicBoolean finished,
        JSObject response,
        String error
    ) {
        mainHandler.post(() -> {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            commandPending.set(false);
            call.setKeepAlive(false);
            if (error == null) {
                call.resolve(response);
            } else {
                call.reject(error);
            }
        });
    }

    private static final class CommandTimeoutException extends IOException {
        CommandTimeoutException(String message) {
            super(message);
        }
    }

    private void registerDisconnectReceiver(String address) {
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        disconnectReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                BluetoothDevice device;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    device = intent.getParcelableExtra(
                        BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice.class
                    );
                } else {
                    device = (BluetoothDevice) intent.getParcelableExtra(
                        BluetoothDevice.EXTRA_DEVICE
                    );
                }
                if (device != null && address.equals(device.getAddress())) {
                    reportConnectionLost("The HC-05 Bluetooth link was disconnected.");
                }
            }
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getContext().registerReceiver(disconnectReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            getContext().registerReceiver(disconnectReceiver, filter);
        }
    }

    private void closeConnection() {
        synchronized (connectionLock) {
            closeConnectionLocked();
        }
    }

    private void reportConnectionLost(String reason) {
        closeConnection();
        JSObject event = new JSObject();
        event.put("reason", reason);
        mainHandler.post(() -> notifyListeners("connectionLost", event));
    }

    private void closeConnectionLocked() {
        BroadcastReceiver receiver = disconnectReceiver;
        disconnectReceiver = null;
        if (receiver != null) {
            try {
                getContext().unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
                // The connection was already closed.
            }
        }
        closeQuietly(socket);
        socket = null;
    }

    private static void closeQuietly(BluetoothSocket bluetoothSocket) {
        if (bluetoothSocket != null) {
            try {
                bluetoothSocket.close();
            } catch (IOException ignored) {
                // Closing an already disconnected socket is harmless.
            }
        }
    }

    @Override
    protected void handleOnDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        finishScan();
        closeConnection();
        io.shutdownNow();
        super.handleOnDestroy();
    }
}
```

## `hardware/arduino/MahindraBeaconMega/MahindraBeaconMega.ino`

```cpp
#include <ArduinoJson.h>
#include <SoftwareSerial.h>

// HC-05 TXD -> Mega D10; Mega D11 -> HC-05 RXD through a logic-level divider.
SoftwareSerial BT(10, 11);

// Verify the relay module's input trigger polarity before changing this setting.
#define MAHINDRA_RELAY_ACTIVE_LOW 1

constexpr uint8_t RELAY_ON_LEVEL = MAHINDRA_RELAY_ACTIVE_LOW ? LOW : HIGH;
constexpr uint8_t RELAY_OFF_LEVEL = MAHINDRA_RELAY_ACTIVE_LOW ? HIGH : LOW;
constexpr size_t MAX_COMMAND_LENGTH = 256;
constexpr uint8_t ADDITIONAL_RELAY_PIN = 13;
constexpr uint32_t RELAY_WATCHDOG_TIMEOUT_MS = 3000;

const uint8_t RELAY_PINS[] = {2, 3, 4, 5, 6, 7, 8, 9, 12, 13, 14, 15, 16, 17, 18, 19};

struct ZoneRelay {
  const char* zoneId;
  uint8_t pin;
};

const ZoneRelay ZONE_RELAYS[] = {
  {"res-3bhk", 2},
  {"res-4bhk", 3},
  {"clubhouse", 4},
  {"service", 5},
  {"cafe", 6},
  {"podium", 7},
  {"balcony", 8},
  {"landscape", 9},
  {"facade", 12},
  {"additional", 13},
};

char commandLine[MAX_COMMAND_LENGTH + 1];
size_t commandLength = 0;
bool commandOverflow = false;
uint32_t lastControllerActivityAt = 0;
bool controllerWatchdogArmed = false;

void markControllerActivity() {
  lastControllerActivityAt = millis();
  controllerWatchdogArmed = true;
}

void sendWatchdogProbeReply(uint32_t commandId) {
  StaticJsonDocument<192> reply;
  reply["commandId"] = commandId;
  reply["acknowledged"] = true;
  reply["watchdog"] = true;
  reply["timeoutMs"] = RELAY_WATCHDOG_TIMEOUT_MS;
  serializeJson(reply, BT);
  BT.write('\n');
  Serial.print(F("TX watchdog ready: "));
  serializeJson(reply, Serial);
  Serial.println();
}

void sendReply(uint32_t commandId, bool acknowledged, const char* zoneId, const char* state, const char* error = nullptr) {
  StaticJsonDocument<192> reply;
  reply["commandId"] = commandId;
  reply["acknowledged"] = acknowledged;
  reply["zoneId"] = zoneId;
  reply["state"] = state;
  if (error != nullptr) {
    reply["error"] = error;
  }
  serializeJson(reply, BT);
  BT.write('\n');
  Serial.print(F("TX: "));
  serializeJson(reply, Serial);
  Serial.println();
}

void handleMasterCommand(JsonDocument& command, uint32_t commandId, const char* state) {
  JsonArrayConst zoneIds = command["zoneIds"].as<JsonArrayConst>();
  if ((strcmp(state, "ON") != 0 && strcmp(state, "OFF") != 0) || zoneIds.isNull() || zoneIds.size() == 0) {
    sendReply(commandId, false, "", state, "invalid_master_command");
    return;
  }

  StaticJsonDocument<1024> reply;
  JsonArray results = reply.createNestedArray("results");
  bool allAcknowledged = true;

  for (JsonVariantConst zoneValue : zoneIds) {
    const char* zoneId = zoneValue.as<const char*>();
    JsonObject result = results.createNestedObject();
    result["zoneId"] = zoneId == nullptr ? "" : zoneId;
    result["state"] = state;
    bool acknowledged = false;
    const char* error = nullptr;

    if (zoneId == nullptr || zoneId[0] == '\0') {
      error = "invalid_zone";
    } else if (strcmp(zoneId, "emergency") == 0) {
      error = "life_safety_not_on_general_relay";
    } else {
      for (const ZoneRelay& zoneRelay : ZONE_RELAYS) {
        if (strcmp(zoneId, zoneRelay.zoneId) == 0) {
          digitalWrite(zoneRelay.pin, strcmp(state, "ON") == 0 ? RELAY_ON_LEVEL : RELAY_OFF_LEVEL);
          acknowledged = true;
          break;
        }
      }
      if (!acknowledged) {
        error = "unknown_zone";
      }
    }

    result["acknowledged"] = acknowledged;
    if (error != nullptr) {
      result["error"] = error;
      allAcknowledged = false;                                      
    }
  }

  reply["commandId"] = commandId;
  reply["acknowledged"] = allAcknowledged;
  reply["state"] = state;
  serializeJson(reply, BT);
  BT.write('\n');
  Serial.print(F("TX: "));
  serializeJson(reply, Serial);
  Serial.println();
}

void handleCommand(const char* line) {
  StaticJsonDocument<512> command;
  const DeserializationError parseError = deserializeJson(command, line);
  if (parseError) {
    sendReply(0, false, "", "", "invalid_json");
    return;
  }

  const uint32_t commandId = command["commandId"] | 0;
  const char* commandType = command["command"] | "zone";
  if (strcmp(commandType, "heartbeat") == 0) {
    markControllerActivity();
    return;
  }
  if (strcmp(commandType, "watchdogProbe") == 0) {
    markControllerActivity();
    sendWatchdogProbeReply(commandId);
    return;
  }
  markControllerActivity();
  if (strcmp(commandType, "master") == 0) {
    handleMasterCommand(command, commandId, command["state"] | "");
    return;
  }

  const char* zoneId = command["zoneId"] | "";
  const char* state = command["state"] | "";
  if (zoneId[0] == '\0' || (strcmp(state, "ON") != 0 && strcmp(state, "OFF") != 0)) {
    sendReply(commandId, false, zoneId, state, "invalid_command");
    return;
  }

  if (strcmp(zoneId, "emergency") == 0) {
    sendReply(commandId, false, zoneId, state, "life_safety_not_on_general_relay");
    return;
  }

  for (const ZoneRelay& zoneRelay : ZONE_RELAYS) {
    if (strcmp(zoneId, zoneRelay.zoneId) == 0) {
      digitalWrite(zoneRelay.pin, strcmp(state, "ON") == 0 ? RELAY_ON_LEVEL : RELAY_OFF_LEVEL);
      sendReply(commandId, true, zoneId, state);
      return;
    }
  }

  sendReply(commandId, false, zoneId, state, "unknown_zone");
}

void setup() {
  Serial.begin(115200);
  for (const uint8_t pin : RELAY_PINS) {
    digitalWrite(pin, RELAY_OFF_LEVEL);
    pinMode(pin, OUTPUT);
  }
  BT.begin(9600);
  lastControllerActivityAt = millis();
  controllerWatchdogArmed = false;
  Serial.println(F("Mahindra Beacon HC-05 controller ready; relays initialized OFF."));
}

void loop() {
  while (BT.available() > 0) {
    const char incoming = static_cast<char>(BT.read());
    if (incoming == '\n') {
      if (commandOverflow) {
        sendReply(0, false, "", "", "command_too_long");
      } else if (commandLength > 0) {
        commandLine[commandLength] = '\0';
        Serial.print(F("RX: "));
        Serial.println(commandLine);
        handleCommand(commandLine);
      }
      commandLength = 0;
      commandOverflow = false;
    } else if (incoming != '\r') {
      if (commandLength < MAX_COMMAND_LENGTH) {
        commandLine[commandLength++] = incoming;
      } else {
        commandOverflow = true;
      }
    }
  }

  if (
    controllerWatchdogArmed
    && static_cast<uint32_t>(millis() - lastControllerActivityAt) >= RELAY_WATCHDOG_TIMEOUT_MS
  ) {
    digitalWrite(ADDITIONAL_RELAY_PIN, RELAY_OFF_LEVEL);
    controllerWatchdogArmed = false;
    Serial.println(F("Relay watchdog expired; Additional Area relay switched OFF."));
  }
}
```

## Hardware setup and safety notes

# Mahindra Beacon Mega 2560 relay firmware

This sketch implements the app's newline-delimited, command-ID JSON protocol over HC-05 Bluetooth Classic. Master ON/OFF applies the requested non-safety zones in one controller operation and returns per-zone results.

## Before uploading

The provided `16_CHANNEL_RELAY.ino` uses `SoftwareSerial` on D10/D11 and relay outputs on D2–D9 and D12–D19. This app-compatible adaptation maps ten zones and holds the other six channels inactive. The sketch currently selects **active-LOW** based on an earlier report, but the observed lamp response is now inverted. Do not treat the trigger polarity or contact wiring as verified: have a qualified person check the relay input trigger and the lamp's COM/NO/NC connection using only isolated low-voltage test lamps before changing this setting or connecting other loads.

Arduino IDE also needs the **ArduinoJson** library (install it from Library Manager).

## Mega 2560 pin assignment

| Mega pin | App lighting zone |
| --- | --- |
| D2 | 3 BHK |
| D3 | 4 BHK |
| D4 | Clubhouse |
| D5 | Service Floor |
| D6 | Cafe |
| D7 | Podium |
| D8 | Swimming Pool |
| D9 | Landscape |
| D12 | Animation |
| D13 | Additional Area |
| D14–D19 | Spare relay channels; held inactive |

`Refuse / Emergency Exit` is deliberately not assigned to a general-purpose Arduino relay. Keep emergency, fire-alarm, and exit-sign circuits on their independently certified life-safety controls.

## HC-05 serial connection

The sketch uses `SoftwareSerial BT(10, 11)` at 9600 baud: HC-05 TXD to Mega D10 (software RX), and Mega D11 (software TX) to HC-05 RXD through a suitable logic-level divider. Connect grounds together and power the module from a stable regulated supply. Pair the HC-05 once in Android settings; the app then scans for and reconnects to the paired HC-05 automatically.

Use the relay module's documented input wiring and a qualified electrician for any mains-connected loads. A JSON acknowledgement confirms that the Arduino set a requested output; it does **not** sense relay contacts or verify that a lamp actually lit.

The controller starts with all mapped outputs inactive. Emergency lighting is never controlled by this sketch, including when a command includes a maintenance flag.

The Android app currently compensates for the observed inverse lamp response by mapping a requested light ON to a relay OFF command, and vice versa. This is a temporary logical inversion, not a sensor-confirmed state. Keep it limited to the isolated low-voltage test lamps until a qualified person verifies relay trigger polarity and COM/NO/NC wiring.

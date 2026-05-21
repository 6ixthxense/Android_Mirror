import React, { useState, useEffect } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { WebviewWindow } from '@tauri-apps/api/webviewWindow';

export default function ControlHub() {
  const [devices, setDevices] = useState([]);
  const [selectedDevice, setSelectedDevice] = useState('');
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [wifiLoading, setWifiLoading] = useState(false);
  const [errorMsg, setErrorMsg] = useState('');

  const [bitrate, setBitrate] = useState(() => parseInt(localStorage.getItem('mirror_bitrate') || '12000000'));
  const [fpsVal, setFpsVal] = useState(() => parseInt(localStorage.getItem('mirror_fps') || '60'));
  const [maxRes, setMaxRes] = useState(() => parseInt(localStorage.getItem('mirror_max_res') || '0'));

  const handleConfigChange = async (newBitrate, newFps, newMaxRes) => {
    localStorage.setItem('mirror_bitrate', newBitrate.toString());
    localStorage.setItem('mirror_fps', newFps.toString());
    localStorage.setItem('mirror_max_res', newMaxRes.toString());
    
    if (selectedDevice) {
      try {
        await invoke('update_config', {
          deviceId: selectedDevice,
          bitrate: parseInt(newBitrate),
          fps: parseInt(newFps),
          maxRes: parseInt(newMaxRes),
        });
      } catch (err) {
        console.error("Failed to update config over ADB:", err);
      }
    }
  };

  const refreshDevices = async () => {
    setIsRefreshing(true);
    setErrorMsg('');
    try {
      const result = await invoke('get_devices');
      // result is already an array in Tauri v2
      setDevices(result);
      if (result.length > 0 && !selectedDevice) {
        setSelectedDevice(result[0]);
      } else if (result.length === 0) {
        setSelectedDevice('');
      }
    } catch (err) {
      console.error(err);
      setErrorMsg(err.toString());
    } finally {
      setIsRefreshing(false);
    }
  };

  useEffect(() => {
    refreshDevices();
    const interval = setInterval(refreshDevices, 3000);
    return () => clearInterval(interval);
  }, []);

  const handleConnect = async () => {
    if (!selectedDevice) return;
    
    // Create the mirror window
    try {
      const windowLabel = `mirror-${Date.now()}`;
      const webview = new WebviewWindow(windowLabel, {
        url: `/#/mirror/${encodeURIComponent(selectedDevice)}`,
        title: `Mirroring: ${selectedDevice}`,
        width: 400,
        height: 800,
        resizable: true,
      });
      
      webview.once('tauri://error', (e) => {
        setErrorMsg('Failed to create mirror window: ' + e.payload);
      });
    } catch (e) {
      setErrorMsg('Window creation error: ' + e.toString());
    }
  };

  const handleWifiMode = async () => {
    if (!selectedDevice) return;
    setWifiLoading(true);
    setErrorMsg('');
    try {
      // Call Rust backend to enable TCP/IP and connect
      const newDeviceIp = await invoke('enable_tcpip', { deviceId: selectedDevice });
      
      // Select the new WiFi device automatically
      setSelectedDevice(newDeviceIp);
      await refreshDevices();
    } catch (err) {
      console.error(err);
      setErrorMsg(err.toString());
    } finally {
      setWifiLoading(false);
    }
  };

  return (
    <div className="flex h-screen w-screen overflow-hidden bg-[#070a13] text-[#e2e8f0] font-sans relative">
      <div className="absolute top-[-10%] left-[-10%] w-[50%] h-[50%] bg-blue-600/10 rounded-full blur-[120px] pointer-events-none"></div>
      
      <div className="w-full max-w-md mx-auto my-auto flex flex-col glass border border-white/5 z-10 p-8 rounded-2xl shadow-2xl">
        <div className="flex items-center space-x-3 mb-8">
          <div className="h-12 w-12 rounded-xl bg-gradient-to-tr from-blue-600 to-indigo-500 flex items-center justify-center shadow-lg shadow-blue-500/20">
            <svg className="w-6 h-6 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 18h.01M8 21h8a2 2 0 002-2V5a2 2 0 00-2-2H8a2 2 0 00-2 2v14a2 2 0 002 2z" />
            </svg>
          </div>
          <div>
            <h1 className="text-2xl font-bold bg-clip-text text-transparent bg-gradient-to-r from-white to-slate-400">Phone Mirror</h1>
            <p className="text-sm text-slate-500">Control Hub</p>
          </div>
        </div>

        <div className="space-y-6">
          <div className="space-y-2">
            <label className="text-xs font-semibold text-slate-400 uppercase tracking-wider">Target Device</label>
            {devices.length === 0 ? (
              <div className="p-4 bg-slate-900/40 rounded-xl border border-white/5 text-center text-sm text-slate-500">
                {isRefreshing ? 'Scanning...' : 'No ADB devices detected. Please plug in your phone via USB.'}
              </div>
            ) : (
              <select
                value={selectedDevice}
                onChange={(e) => setSelectedDevice(e.target.value)}
                className="w-full px-4 py-3 bg-slate-900/60 rounded-xl border border-white/10 text-sm focus:outline-none focus:border-blue-500 transition appearance-none cursor-pointer"
              >
                {devices.map((d) => (
                  <option key={d} value={d} className="bg-slate-950">{d}</option>
                ))}
              </select>
            )}
          </div>

          {/* Quality Control Hub */}
          <div className="space-y-4 pt-4 border-t border-white/10">
            <label className="text-xs font-semibold text-slate-400 uppercase tracking-wider block">Quality Settings</label>
            
            {/* Resolution */}
            <div className="space-y-2">
              <span className="text-xs text-slate-400">Resolution</span>
              <div className="flex bg-slate-900/60 p-1 rounded-xl border border-white/5">
                {[
                  { label: '720p', value: 720 },
                  { label: '1080p', value: 1080 },
                  { label: 'Native', value: 0 },
                ].map((r) => (
                  <button
                    key={r.value}
                    onClick={() => {
                      setMaxRes(r.value);
                      handleConfigChange(bitrate, fpsVal, r.value);
                    }}
                    className={`flex-1 text-center py-2 text-xs font-medium rounded-lg transition-all ${
                      maxRes === r.value 
                        ? 'bg-blue-600 text-white shadow-md' 
                        : 'text-slate-400 hover:text-white'
                    }`}
                  >
                    {r.label}
                  </button>
                ))}
              </div>
            </div>

            {/* Framerate */}
            <div className="space-y-2">
              <span className="text-xs text-slate-400">Frame Rate</span>
              <div className="flex bg-slate-900/60 p-1 rounded-xl border border-white/5">
                {[
                  { label: '30 FPS', value: 30 },
                  { label: '60 FPS', value: 60 },
                ].map((f) => (
                  <button
                    key={f.value}
                    onClick={() => {
                      setFpsVal(f.value);
                      handleConfigChange(bitrate, f.value, maxRes);
                    }}
                    className={`flex-1 text-center py-2 text-xs font-medium rounded-lg transition-all ${
                      fpsVal === f.value 
                        ? 'bg-indigo-600 text-white shadow-md' 
                        : 'text-slate-400 hover:text-white'
                    }`}
                  >
                    {f.label}
                  </button>
                ))}
              </div>
            </div>

            {/* Bitrate Slider */}
            <div className="space-y-2">
              <div className="flex justify-between items-center text-xs">
                <span className="text-slate-400">Bitrate</span>
                <span className="text-blue-400 font-semibold">{Math.round(bitrate / 1000000)} Mbps</span>
              </div>
              <input
                type="range"
                min="2000000"
                max="20000000"
                step="1000000"
                value={bitrate}
                onChange={(e) => {
                  const val = parseInt(e.target.value);
                  setBitrate(val);
                }}
                onMouseUp={() => handleConfigChange(bitrate, fpsVal, maxRes)}
                onTouchEnd={() => handleConfigChange(bitrate, fpsVal, maxRes)}
                className="w-full h-1.5 bg-slate-800 rounded-lg appearance-none cursor-pointer accent-blue-500"
              />
              <div className="flex justify-between text-[10px] text-slate-500 px-0.5">
                <span>2 Mbps</span>
                <span>10 Mbps</span>
                <span>20 Mbps</span>
              </div>
            </div>
          </div>

          <div className="flex space-x-4">
            <button
              onClick={handleConnect}
              disabled={devices.length === 0}
              className="flex-1 bg-blue-600 hover:bg-blue-500 disabled:opacity-50 disabled:hover:bg-blue-600 text-white font-medium py-3 rounded-xl transition shadow-lg shadow-blue-500/20"
            >
              Open Mirror Window
            </button>
          </div>
          
          <div className="pt-4 border-t border-white/10">
            <button
              onClick={handleWifiMode}
              disabled={devices.length === 0 || wifiLoading}
              className="w-full bg-slate-800 hover:bg-slate-700 disabled:opacity-50 text-white font-medium py-3 rounded-xl transition border border-white/5 flex justify-center items-center space-x-2"
            >
              <svg className="w-5 h-5 text-purple-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8.111 16.404a5.5 5.5 0 017.778 0M12 20h.01m-7.08-7.071c3.904-3.905 10.236-3.905 14.141 0M1.394 9.393c5.857-5.857 15.355-5.857 21.213 0" />
              </svg>
              <span>{wifiLoading ? 'Switching to WiFi...' : 'Switch to WiFi Mode'}</span>
            </button>
            <p className="text-xs text-slate-500 mt-3 text-center">Connect via USB first, then switch to WiFi mode to mirror wirelessly.</p>
          </div>

          {errorMsg && (
            <div className="p-3 bg-rose-500/10 border border-rose-500/20 rounded-xl text-rose-400 text-sm break-words">
              {errorMsg}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

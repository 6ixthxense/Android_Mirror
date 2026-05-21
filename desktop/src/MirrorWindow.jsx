import React, { useState, useEffect, useRef } from 'react';
import { invoke, Channel } from '@tauri-apps/api/core';
import { useParams } from 'react-router-dom';
import { getCurrentWindow, LogicalSize } from '@tauri-apps/api/window';

// Raw PCM 16-bit 44100Hz Stereo Audio Player
class PcmPlayer {
  constructor() {
    this.audioCtx = null;
    this.nextStartTime = 0;
  }

  init() {
    if (this.audioCtx) return;
    this.audioCtx = new (window.AudioContext || window.webkitAudioContext)({
      sampleRate: 44100,
      latencyHint: 'interactive'
    });
    this.nextStartTime = this.audioCtx.currentTime;
  }

  feed(bytes) {
    if (!this.audioCtx) this.init();
    if (this.audioCtx.state === 'suspended') {
      this.audioCtx.resume();
    }

    const sampleCount = bytes.length / 2; // each sample is 16-bit (2 bytes)
    const channelSampleCount = sampleCount / 2; // stereo: 2 channels
    if (channelSampleCount <= 0) return;

    const leftChannel = new Float32Array(channelSampleCount);
    const rightChannel = new Float32Array(channelSampleCount);

    const dataView = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    let channelIdx = 0;
    for (let i = 0; i < sampleCount; i += 2) {
      const leftVal = dataView.getInt16(i * 2, true);
      const rightVal = dataView.getInt16((i + 1) * 2, true);
      leftChannel[channelIdx] = leftVal / 32768.0;
      rightChannel[channelIdx] = rightVal / 32768.0;
      channelIdx++;
    }

    const audioBuffer = this.audioCtx.createBuffer(2, channelSampleCount, 44100);
    audioBuffer.copyToChannel(leftChannel, 0);
    audioBuffer.copyToChannel(rightChannel, 1);

    const source = this.audioCtx.createBufferSource();
    source.buffer = audioBuffer;
    source.connect(this.audioCtx.destination);

    let playTime = this.nextStartTime;
    const currentTime = this.audioCtx.currentTime;
    
    if (playTime < currentTime) {
      playTime = currentTime + 0.02; // 20ms queue offset for jitter smoothing
    }

    source.start(playTime);
    this.nextStartTime = playTime + audioBuffer.duration;
  }

  destroy() {
    if (this.audioCtx) {
      this.audioCtx.close();
      this.audioCtx = null;
    }
  }
}

export default function MirrorWindow() {
  const { deviceId } = useParams();
  const decodedDeviceId = decodeURIComponent(deviceId);
  
  const [status, setStatus] = useState('CONNECTING'); // CONNECTING, CONNECTED, ERROR
  const [errorMsg, setErrorMsg] = useState('');
  const [focusMode, setFocusMode] = useState(true);
  const [fps, setFps] = useState(0);

  const canvasRef = useRef(null);
  const isConnectedRef = useRef(true);
  const videoDecoderRef = useRef(null);
  const audioPlayerRef = useRef(null);
  const frameCountRef = useRef(0);
  const fpsIntervalRef = useRef(null);

  // Target screen resolution
  const phoneWidthRef = useRef(0);
  const phoneHeightRef = useRef(0);

  // Draw decoded VideoFrame to Canvas
  const drawFrame = (frame) => {
    if (!canvasRef.current) {
      frame.close();
      return;
    }
    const ctx = canvasRef.current.getContext('2d');
    
    // Auto-resize canvas if needed
    if (canvasRef.current.width !== phoneWidthRef.current || canvasRef.current.height !== phoneHeightRef.current) {
      canvasRef.current.width = phoneWidthRef.current;
      canvasRef.current.height = phoneHeightRef.current;
      
      // Auto-resize window to match aspect ratio and screen boundaries
      const win = getCurrentWindow();
      const aspectRatio = phoneWidthRef.current / phoneHeightRef.current;
      
      let newWidth, newHeight;
      if (aspectRatio < 1) {
        // Portrait: bound by height
        newHeight = Math.min(850, phoneHeightRef.current);
        newWidth = newHeight * aspectRatio;
      } else {
        // Landscape: bound by width or height
        newWidth = Math.min(1200, phoneWidthRef.current);
        newHeight = newWidth / aspectRatio;
        if (newHeight > 800) {
          newHeight = 800;
          newWidth = newHeight * aspectRatio;
        }
      }
      
      newWidth = Math.round(newWidth);
      newHeight = Math.round(newHeight);
      
      win.setSize(new LogicalSize(newWidth, newHeight)).catch(console.error);
    }
    
    ctx.drawImage(frame, 0, 0, phoneWidthRef.current, phoneHeightRef.current);
    frame.close();
    frameCountRef.current++;
  };

  const handleDisconnect = async () => {
    isConnectedRef.current = false;
    setStatus('DISCONNECTED');
    if (videoDecoderRef.current && videoDecoderRef.current.state !== 'closed') {
      try { videoDecoderRef.current.close(); } catch (e) {}
    }
    if (audioPlayerRef.current) {
      try { audioPlayerRef.current.destroy(); } catch (e) {}
      audioPlayerRef.current = null;
    }
    try {
      await invoke('stop_mirroring');
    } catch (err) {
      console.error(err);
    }
    const win = getCurrentWindow();
    win.close();
  };

  useEffect(() => {
    // Keep track of FPS
    if (status === 'CONNECTED') {
      fpsIntervalRef.current = setInterval(() => {
        setFps(frameCountRef.current);
        frameCountRef.current = 0;
      }, 1000);
    } else {
      setFps(0);
      if (fpsIntervalRef.current) clearInterval(fpsIntervalRef.current);
    }
    return () => {
      if (fpsIntervalRef.current) clearInterval(fpsIntervalRef.current);
    };
  }, [status]);

  // Touch/Mouse event handlers
  const handlePointerDown = async (e) => {
    if (!canvasRef.current || !focusMode) return;
    e.target.setPointerCapture(e.pointerId);
    await sendTouchEvent('DOWN', e);
  };
  const handlePointerMove = async (e) => {
    if (!canvasRef.current || !focusMode) return;
    if (e.buttons > 0) { // Only send move if button is held
      await sendTouchEvent('MOVE', e);
    }
  };
  const handlePointerUp = async (e) => {
    if (!canvasRef.current || !focusMode) return;
    e.target.releasePointerCapture(e.pointerId);
    await sendTouchEvent('UP', e);
  };

  const sendTouchEvent = async (action, e) => {
    const rect = canvasRef.current.getBoundingClientRect();
    const scaleX = phoneWidthRef.current / rect.width;
    const scaleY = phoneHeightRef.current / rect.height;
    
    // Calculate logical X and Y mapped to original phone resolution
    const x = Math.max(0, Math.min(Math.round((e.clientX - rect.left) * scaleX), phoneWidthRef.current));
    const y = Math.max(0, Math.min(Math.round((e.clientY - rect.top) * scaleY), phoneHeightRef.current));

    try {
      await invoke('inject_event', { event: `TOUCH_${action} ${x} ${y}` });
    } catch (err) {
      console.error(err);
    }
  };

  // Translate JS Key Events to Android keycodes
  const translateToAndroidKeycode = (code) => {
    if (code.startsWith('Key')) return code.charCodeAt(3) - 65 + 29;
    if (code.startsWith('Digit')) {
      const digit = code.charAt(5);
      if (digit === '0') return 7;
      return parseInt(digit) + 7;
    }
    const map = {
      'Enter': 66, 'Escape': 111, 'Backspace': 67, 'Space': 62,
      'ArrowUp': 19, 'ArrowDown': 20, 'ArrowLeft': 21, 'ArrowRight': 22, 'Tab': 61,
    };
    return map[code] || null;
  };

  useEffect(() => {
    const handleKeyDown = async (e) => {
      if (status !== 'CONNECTED' || !focusMode) return;
      if (['Space', 'Backspace', 'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Tab'].includes(e.code)) e.preventDefault();
      const keycode = translateToAndroidKeycode(e.code);
      if (keycode !== null) await invoke('inject_event', { event: `KEY_DOWN ${keycode}` });
    };
    const handleKeyUp = async (e) => {
      if (status !== 'CONNECTED' || !focusMode) return;
      if (['Space', 'Backspace', 'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Tab'].includes(e.code)) e.preventDefault();
      const keycode = translateToAndroidKeycode(e.code);
      if (keycode !== null) await invoke('inject_event', { event: `KEY_UP ${keycode}` });
    };

    window.addEventListener('keydown', handleKeyDown);
    window.addEventListener('keyup', handleKeyUp);
    return () => {
      window.removeEventListener('keydown', handleKeyDown);
      window.removeEventListener('keyup', handleKeyUp);
    };
  }, [status, focusMode]);

  useEffect(() => {
    const connectToDevice = async () => {
      isConnectedRef.current = true;
      try {
        let accumulator = new Uint8Array(0);
        let headerParsed = false;
        let spsPpsBuffer = null;

        const appendBytes = (newBytes) => {
          const merged = new Uint8Array(accumulator.length + newBytes.length);
          merged.set(accumulator);
          merged.set(newBytes, accumulator.length);
          accumulator = merged;
        };

        const consumeBytes = (count) => {
          const consumed = accumulator.slice(0, count);
          accumulator = accumulator.slice(count);
          return consumed;
        };

        const initializeDecoder = (width, height) => {
          if (videoDecoderRef.current && videoDecoderRef.current.state !== 'closed') {
             videoDecoderRef.current.close();
          }
          videoDecoderRef.current = new VideoDecoder({
            output: drawFrame,
            error: (e) => {
              console.error('WebCodecs Decoder Error:', e);
              setErrorMsg(`Decoder Error: ${e.message}`);
            }
          });
          videoDecoderRef.current.configure({
            codec: 'avc1.640033', // H.264 High Profile Level 5.1 (Annex B) for max compatibility and performance at high resolutions
            codedWidth: width,
            codedHeight: height,
            optimizeForLatency: true
          });
        };

        const channel = new Channel();
        channel.onmessage = (payload) => {
          try {
            if (!isConnectedRef.current) return;
            
            let bytes;
            if (payload instanceof Uint8Array) bytes = payload;
            else if (Array.isArray(payload)) bytes = new Uint8Array(payload);
            else if (payload && payload.data) bytes = new Uint8Array(payload.data);
            else if (typeof payload === 'string') {
              const binString = atob(payload);
              bytes = new Uint8Array(binString.length);
              for (let i = 0; i < binString.length; i++) bytes[i] = binString.charCodeAt(i);
            } else bytes = new Uint8Array(0);

            appendBytes(bytes);

            // 1. Parse resolution header (8 bytes: [4 width][4 height])
            if (!headerParsed) {
              if (accumulator.length >= 8) {
                const view = new DataView(accumulator.buffer, accumulator.byteOffset, 8);
                const width = view.getInt32(0, false);
                const height = view.getInt32(4, false);
                consumeBytes(8);
                
                phoneWidthRef.current = width;
                phoneHeightRef.current = height;
                headerParsed = true;
                
                initializeDecoder(width, height);
                setStatus('CONNECTED');
              } else {
                return;
              }
            }

            // 2. Parse packetized stream: [4 size][1 flags][8 timestamp][payload]
            while (headerParsed && accumulator.length >= 13) {
              const view = new DataView(accumulator.buffer, accumulator.byteOffset, 13);
              const payloadSize = view.getUint32(0, false);
              
              // Handle Auto-Rotation signal
              if (payloadSize === 0xFFFFFFFF) {
                // Rotation signal is 12 bytes: [4-byte -1] [4-byte width] [4-byte height]
                if (accumulator.length >= 12) {
                  const rotationView = new DataView(accumulator.buffer, accumulator.byteOffset, 12);
                  const width = rotationView.getInt32(4, false);
                  const height = rotationView.getInt32(8, false);
                  consumeBytes(12);
                  
                  phoneWidthRef.current = width;
                  phoneHeightRef.current = height;
                  
                  initializeDecoder(width, height);
                  spsPpsBuffer = null;
                  continue; // Continue parsing next packets
                } else {
                  return; // Wait for more bytes
                }
              }

              if (payloadSize > 5000000) {
                 setErrorMsg(`Out of sync! Payload size: ${payloadSize}`);
                 handleDisconnect();
                 return;
              }

              const totalPacketSize = 13 + payloadSize;
              if (accumulator.length >= totalPacketSize) {
                consumeBytes(13); // Consume header
                const payloadData = consumeBytes(payloadSize); // Consume payload
                const flags = view.getUint8(4);
                const timestamp = Number(view.getBigInt64(5, false));

                if ((flags & 0x02) !== 0) {
                  spsPpsBuffer = new Uint8Array(payloadData);
                  continue;
                }

                if (videoDecoderRef.current && videoDecoderRef.current.state === 'configured') {
                  const isKey = (flags & 0x01) !== 0;
                  let chunkData = payloadData;
                  
                  if (isKey && spsPpsBuffer && spsPpsBuffer.length > 0) {
                    const combined = new Uint8Array(spsPpsBuffer.length + payloadData.length);
                    combined.set(spsPpsBuffer);
                    combined.set(payloadData, spsPpsBuffer.length);
                    chunkData = combined;
                  }

                  try {
                    const chunk = new EncodedVideoChunk({
                      type: isKey ? 'key' : 'delta',
                      timestamp: timestamp,
                      data: chunkData
                    });
                    videoDecoderRef.current.decode(chunk);
                    setErrorMsg('');
                  } catch (e) {
                     setErrorMsg(`Decode Exception: ${e.message}`);
                  }
                }
              } else {
                break;
              }
            }
          } catch (e) {
            setErrorMsg(`Channel Error: ${e.message}`);
          }
        };

        const audioChannel = new Channel();
        audioChannel.onmessage = (payload) => {
          try {
            if (!isConnectedRef.current) return;
            let bytes;
            if (payload instanceof Uint8Array) bytes = payload;
            else if (Array.isArray(payload)) bytes = new Uint8Array(payload);
            else if (payload && payload.data) bytes = new Uint8Array(payload.data);
            else if (typeof payload === 'string') {
              const binString = atob(payload);
              bytes = new Uint8Array(binString.length);
              for (let i = 0; i < binString.length; i++) bytes[i] = binString.charCodeAt(i);
            } else return;

            if (audioPlayerRef.current) {
              audioPlayerRef.current.feed(bytes);
            }
          } catch (e) {
            console.error("Audio feed error:", e);
          }
        };

        const bitrate = parseInt(localStorage.getItem('mirror_bitrate') || '12000000');
        const fpsVal = parseInt(localStorage.getItem('mirror_fps') || '60');
        const maxRes = parseInt(localStorage.getItem('mirror_max_res') || '0');

        audioPlayerRef.current = new PcmPlayer();

        await invoke('start_mirroring', { 
          deviceId: decodedDeviceId,
          bitrate: bitrate,
          fps: fpsVal,
          maxRes: maxRes,
          onFrame: channel,
          onAudio: audioChannel
        });

      } catch (err) {
        console.error(err);
        setStatus('ERROR');
        setErrorMsg(err.toString());
      }
    };
    
    connectToDevice();
    
    return () => {
      isConnectedRef.current = false;
    };
  }, [decodedDeviceId]);

  return (
    <div className="flex h-screen w-screen bg-black text-white relative">
      <div className="absolute top-2 left-2 z-50 pointer-events-none flex space-x-2">
        <span className="px-2 py-1 bg-black/50 backdrop-blur rounded text-xs text-white">FPS: {fps}</span>
        {errorMsg && <span className="px-2 py-1 bg-rose-500/80 rounded text-xs text-white">{errorMsg}</span>}
      </div>
      
      {!focusMode && (
         <div 
           className="absolute top-2 right-2 z-50 bg-blue-600 px-3 py-1 rounded text-xs cursor-pointer shadow-lg"
           onClick={() => setFocusMode(true)}
         >
           Enable Keyboard
         </div>
      )}

      {status === 'CONNECTING' ? (
        <div className="m-auto flex flex-col items-center">
           <svg className="animate-spin h-8 w-8 text-blue-500 mb-4" fill="none" viewBox="0 0 24 24">
             <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
             <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
           </svg>
           <p className="text-slate-400">Connecting to {decodedDeviceId}...</p>
        </div>
      ) : status === 'ERROR' ? (
        <div className="m-auto text-rose-500">
           Connection Failed: {errorMsg}
        </div>
      ) : (
        <div className="w-full h-full flex items-center justify-center relative overflow-hidden" 
             onClick={() => setFocusMode(true)}>
          <canvas
            ref={canvasRef}
            onPointerDown={handlePointerDown}
            onPointerMove={handlePointerMove}
            onPointerUp={handlePointerUp}
            className="max-h-full max-w-full object-contain cursor-crosshair active:cursor-grabbing"
            style={{ touchAction: 'none' }}
          />
        </div>
      )}
    </div>
  );
}

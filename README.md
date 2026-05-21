# Android Mirror (Phone Mirror)

An ultra-low latency, capture-card replacement screen mirroring and audio forwarding application for Android devices to PC.

## Features

- **Ultra-low Latency Mirroring**: Under 50ms display latency using Android `MediaProjection` + WebCodecs decoder on PC.
- **Audio Forwarding**: Stream system-wide stereo audio from Android to PC using `AudioPlaybackCapture` and Web Audio API.
- **Real-time Quality Control Hub**: Adjust bitrate (2-20 Mbps), frame rate (30/60 FPS), and resolution (720p, 1080p, Native) on the fly without disconnecting the stream.
- **Keyboard and Touch Control**: Interact with your Android screen using mouse touch/drag gestures and keyboard inputs.
- **WiFi Mirroring**: Connect once via USB, switch to WiFi mode, and mirror wirelessly.

## Project Structure

- `/android` - Android app code written in Kotlin.
- `/desktop` - Desktop client code built with Tauri and React.
- `/bin` - Build and execution tooling dependencies (ignored from Git).

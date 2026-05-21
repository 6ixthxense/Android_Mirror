import React from 'react';
import { Routes, Route } from 'react-router-dom';
import ControlHub from './ControlHub';
import MirrorWindow from './MirrorWindow';

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<ControlHub />} />
      <Route path="/mirror/:deviceId" element={<MirrorWindow />} />
    </Routes>
  );
}

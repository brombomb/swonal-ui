import React, { useEffect, useState } from 'react';
import { KioskView } from './components/KioskView';
import { useSwonalState } from './hooks/useSwonalState';

export function App() {
  const swonal = useSwonalState();
  const [pathname] = useState(() =>
    typeof window !== 'undefined' ? window.location.pathname : '/'
  );
  const [scale, setScale] = useState(1);

  const isCompanion = pathname.startsWith('/companion');

  useEffect(() => {
    if (isCompanion) return;
    const updateScale = () => {
      const vw = window.innerWidth;
      const vh = window.innerHeight;
      // On native 1080x1920 Tonal portrait screen, scale = 1. On desktop monitors, fit cleanly.
      const s = Math.min(vw / 1080, vh / 1920, 1);
      setScale(s);
    };
    updateScale();
    window.addEventListener('resize', updateScale);
    return () => window.removeEventListener('resize', updateScale);
  }, [isCompanion]);

  if (isCompanion) {
    return (
      <div className="min-h-screen flex items-center justify-center p-6">
        <p className="text-lg font-bold">Companion View Loading (Milestone 5)...</p>
      </div>
    );
  }

  return (
    <div
      className="w-screen h-screen flex items-center justify-center overflow-hidden"
      style={{ backgroundColor: 'var(--bg-canvas)' }}
    >
      <div
        style={{
          width: 1080,
          height: 1920,
          transform: `scale(${scale})`,
          transformOrigin: 'center center',
        }}
      >
        <KioskView swonal={swonal} />
      </div>
    </div>
  );
}

export default App;

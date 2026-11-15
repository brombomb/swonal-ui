import React, { useEffect, useState } from 'react';
import { CompanionView } from './components/CompanionView';
import { KioskView } from './components/KioskView';
import { SettingsView } from './components/SettingsView';
import { useSwonalState } from './hooks/useSwonalState';

export function App() {
  const swonal = useSwonalState();
  const [pathname] = useState(() =>
    typeof window !== 'undefined' ? window.location.pathname : '/'
  );
  const [scale, setScale] = useState(1);

  const isCompanion = pathname.startsWith('/companion');
  const isSettings = pathname.startsWith('/settings');

  useEffect(() => {
    if (isCompanion || isSettings) return;
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
  }, [isCompanion, isSettings]);

  if (isSettings) {
    return <SettingsView swonal={swonal} />;
  }

  if (isCompanion) {
    return <CompanionView swonal={swonal} />;
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

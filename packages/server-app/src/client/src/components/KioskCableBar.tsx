import React, { useEffect, useRef } from 'react';
import { TelemetryFrame } from '../types';
import { formatDisplayWeight } from '../utils/units';

interface KioskCableBarProps {
  side: 'left' | 'right';
  label: string;
  activeUnit: 'LB' | 'KG';
  initialCable: { position: number; velocity: number; load_lb: number };
  subscribeLiveTelemetry: (listener: (frame: TelemetryFrame) => void) => () => void;
}

const INNER_TRACK_TRAVEL_PX = 436; // 494px inner track - 58px puck

export const KioskCableBar: React.FC<KioskCableBarProps> = React.memo(
  ({ side, label, activeUnit, initialCable, subscribeLiveTelemetry }) => {
    const puckRef = useRef<HTMLDivElement>(null);
    const trailRef = useRef<HTMLDivElement>(null);
    const loadRef = useRef<HTMLParagraphElement>(null);
    const velRef = useRef<HTMLParagraphElement>(null);
    const romRef = useRef<HTMLParagraphElement>(null);

    const applyCable = (cable: { position: number; velocity: number; load_lb: number }) => {
      const travelPct = Math.max(0, Math.min((cable.position / 200) * 100, 100));
      const offsetPx = (travelPct / 100) * INNER_TRACK_TRAVEL_PX;
      if (puckRef.current) {
        puckRef.current.style.transform = `translate3d(0, ${-offsetPx.toFixed(1)}px, 0)`;
      }
      if (trailRef.current) {
        const trailFillPx = travelPct > 0 ? offsetPx + 29 : 0;
        trailRef.current.style.transform = `scaleY(${(trailFillPx / 496).toFixed(4)})`;
        trailRef.current.style.opacity = travelPct > 0 ? '0.3' : '0';
      }
      if (loadRef.current) {
        loadRef.current.textContent = `${formatDisplayWeight(cable.load_lb, activeUnit, true)} ${activeUnit}`;
      }
      if (velRef.current) {
        velRef.current.textContent =
          activeUnit === 'LB'
            ? `${(cable.velocity / 2.54).toFixed(1)} in/s`
            : `${cable.velocity.toFixed(1)} cm/s`;
      }
      if (romRef.current) {
        romRef.current.textContent = `ROM ${cable.position.toFixed(0)}`;
      }
    };

    useEffect(() => {
      applyCable(initialCable);
      return subscribeLiveTelemetry((frame) => {
        applyCable(side === 'left' ? frame.left_cable : frame.right_cable);
      });
    }, [side, activeUnit, subscribeLiveTelemetry]);

    const initialPct = Math.max(0, Math.min((initialCable.position / 200) * 100, 100));
    const initialOffsetPx = (initialPct / 100) * INNER_TRACK_TRAVEL_PX;
    const initialTrailFillPx = initialPct > 0 ? initialOffsetPx + 29 : 0;

    return (
      <div className="w-32 flex flex-col items-center gap-3 shrink-0">
        <span className="text-sm font-mono font-black uppercase tracking-widest opacity-75">
          {label}
        </span>
        <div className="relative w-16 h-[500px] elevated-pill rounded-full overflow-hidden">
          <div className="absolute inset-[2px] rounded-[29px] overflow-hidden">
            <div
              ref={trailRef}
              className="absolute inset-x-0 bottom-0 h-full origin-bottom rounded-b-[29px]"
              style={{
                backgroundColor: 'var(--accent-primary)',
                opacity: initialPct > 0 ? 0.3 : 0,
                transform: `scaleY(${(initialTrailFillPx / 496).toFixed(4)})`,
                willChange: 'transform',
              }}
            />
            <div
              ref={puckRef}
              className="absolute inset-x-0 bottom-0 h-[58px] rounded-full"
              style={{
                backgroundColor: 'var(--accent-primary)',
                boxShadow: '0 0 20px var(--accent-glow)',
                transform: `translate3d(0, ${-initialOffsetPx.toFixed(1)}px, 0)`,
                willChange: 'transform',
              }}
            />
          </div>
        </div>
        <div className="w-full text-center font-mono tabular-nums space-y-0.5">
          <p ref={loadRef} className="text-xl font-black whitespace-nowrap">
            {formatDisplayWeight(initialCable.load_lb, activeUnit, true)} {activeUnit}
          </p>
          <p ref={velRef} className="text-base font-bold opacity-90 whitespace-nowrap">
            {activeUnit === 'LB'
              ? `${(initialCable.velocity / 2.54).toFixed(1)} in/s`
              : `${initialCable.velocity.toFixed(1)} cm/s`}
          </p>
          <p ref={romRef} className="text-sm font-semibold opacity-75 whitespace-nowrap">
            ROM {initialCable.position.toFixed(0)}
          </p>
        </div>
      </div>
    );
  }
);

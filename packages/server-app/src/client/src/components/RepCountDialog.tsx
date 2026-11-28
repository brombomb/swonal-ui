import React, { useEffect, useRef, useState } from 'react';
import { X } from 'lucide-react';
import { parseEnteredReps, RepConfirmRequest } from '../utils/setReps';

interface RepCountDialogProps {
  /** Open while non-null (set by `logCompletedSet` when COMPLETED REPS is 0). */
  request: RepConfirmRequest | null;
  accentTextColor: string;
  onConfirm: (reps: number) => void;
  onCancel: () => void;
}

/**
 * "No reps counted" prompt (upstream issue #16): asks how many reps to log instead of
 * guessing. Prefilled with the target (10 in Free Lift); the first key replaces the prefill.
 */
export const RepCountDialog: React.FC<RepCountDialogProps> = ({
  request,
  accentTextColor,
  onConfirm,
  onCancel,
}) => {
  const dialogRef = useRef<HTMLDialogElement | null>(null);
  const [draft, setDraft] = useState('');
  const [replaceNext, setReplaceNext] = useState(true);

  useEffect(() => {
    const dlg = dialogRef.current;
    if (!dlg) return;
    if (request) {
      setDraft(String(request.suggestedReps));
      setReplaceNext(true);
      if (!dlg.open) dlg.showModal();
    } else if (dlg.open) {
      dlg.close();
    }
  }, [request]);

  const reps = parseEnteredReps(draft);

  const pressKey = (key: string) => {
    if (key === 'Clear') {
      setDraft('');
    } else if (key === '⌫') {
      setDraft((prev) => prev.slice(0, -1));
    } else {
      setDraft((prev) =>
        replaceNext || prev === '0' ? key : prev.length < 3 ? `${prev}${key}` : prev
      );
    }
    setReplaceNext(false);
  };

  return (
    <dialog
      ref={dialogRef}
      closedby="any"
      onCancel={(e) => {
        e.preventDefault();
        onCancel();
      }}
      onClose={() => {
        // Light dismiss (closedby="any") can close the dialog natively; keep the hook in sync.
        if (request) onCancel();
      }}
      aria-labelledby="rep-count-title"
      className="rounded-3xl p-7 max-w-sm w-full"
    >
      <div className="flex items-center justify-between mb-2">
        <h2 id="rep-count-title" className="text-xl font-black">
          No reps counted
        </h2>
        <button
          type="button"
          onClick={onCancel}
          className="elevated-pill p-2 rounded-xl"
          aria-label="Cancel"
        >
          <X size={18} />
        </button>
      </div>
      <p className="text-sm opacity-70 mb-4">
        The rep counter didn&apos;t pick up this set. How many reps did you do?
      </p>

      <div className="elevated-pill rounded-2xl px-5 py-4 text-center mb-4">
        <span className="font-mono tabular-nums text-5xl font-black">{draft || '0'}</span>
        <span className="ml-2 text-lg font-bold opacity-65">REPS</span>
      </div>

      <div className="grid grid-cols-3 gap-2 mb-4">
        {['1', '2', '3', '4', '5', '6', '7', '8', '9', 'Clear', '0', '⌫'].map((key) => (
          <button
            key={key}
            type="button"
            onClick={() => pressKey(key)}
            className="elevated-pill py-3.5 rounded-2xl font-mono font-black text-xl hover:opacity-85"
          >
            {key}
          </button>
        ))}
      </div>

      <div className="grid grid-cols-2 gap-2">
        <button
          type="button"
          onClick={onCancel}
          className="elevated-pill py-3.5 rounded-2xl font-bold text-base hover:opacity-90"
        >
          Cancel
        </button>
        <button
          type="button"
          disabled={reps === null}
          onClick={() => {
            if (reps !== null) onConfirm(reps);
          }}
          className="py-3.5 rounded-2xl font-black text-base disabled:opacity-40"
          style={{ backgroundColor: 'var(--accent-primary)', color: accentTextColor }}
        >
          Log Set
        </button>
      </div>
    </dialog>
  );
};

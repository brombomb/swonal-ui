import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import './index.css';

// Lightweight <dialog> polyfill for older browsers (e.g. Firefox 68 ESR on Tonal V1)
if (
  typeof window !== 'undefined' &&
  (typeof window.HTMLDialogElement === 'undefined' ||
    typeof window.HTMLDialogElement.prototype.showModal !== 'function')
) {
  const getOrCreateBackdrop = () => {
    let backdrop = document.getElementById('swonal-dialog-backdrop');
    if (!backdrop) {
      backdrop = document.createElement('div');
      backdrop.id = 'swonal-dialog-backdrop';
      backdrop.style.cssText =
        'position:fixed;inset:0;background:rgba(7,9,14,0.78);z-index:49;display:none;';
      backdrop.addEventListener('click', () => {
        const openDialogs = document.querySelectorAll('dialog[open]');
        const top = openDialogs[openDialogs.length - 1] as HTMLDialogElement | undefined;
        if (top && typeof top.close === 'function') {
          top.close();
        }
      });
      document.body.appendChild(backdrop);
    }
    return backdrop;
  };

  const proto = (window.HTMLDialogElement?.prototype ?? window.HTMLElement.prototype) as {
    showModal?: () => void;
    close?: (returnValue?: string) => void;
  };

  proto.showModal = function (this: HTMLElement & { open?: boolean }) {
    const backdrop = getOrCreateBackdrop();
    backdrop.style.display = 'block';
    this.setAttribute('open', '');
    this.style.display = 'block';
    this.style.position = 'fixed';
    this.style.inset = '0';
    this.style.margin = 'auto';
    this.style.zIndex = '50';
  };

  proto.close = function (this: HTMLElement & { returnValue?: string }, returnValue?: string) {
    if (returnValue !== undefined) {
      this.returnValue = returnValue;
    }
    this.removeAttribute('open');
    this.style.display = 'none';
    const remaining = document.querySelectorAll('dialog[open]');
    if (remaining.length === 0) {
      const backdrop = document.getElementById('swonal-dialog-backdrop');
      if (backdrop) backdrop.style.display = 'none';
    }
    this.dispatchEvent(new Event('close'));
  };

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      const openDialogs = document.querySelectorAll('dialog[open]');
      const top = openDialogs[openDialogs.length - 1] as HTMLDialogElement | undefined;
      if (top && typeof top.close === 'function') {
        top.close();
      }
    }
  });
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);

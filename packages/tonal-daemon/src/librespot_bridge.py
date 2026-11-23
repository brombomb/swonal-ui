"""Supervises on-device librespot (--backend pipe) and relays 44.1kHz S16LE stereo PCM over TCP :41478 to Android AudioTrack."""
from __future__ import annotations

import asyncio
from collections import deque
import functools
import json
import logging
import os
from pathlib import Path
import subprocess
import time
import urllib.request
from typing import Any, Callable, Optional

logger = logging.getLogger("tonal-daemon.librespot")


async def _to_thread(fn: Callable[..., Any], *args: Any, **kwargs: Any) -> Any:
    if hasattr(asyncio, "to_thread"):
        return await asyncio.to_thread(fn, *args, **kwargs)
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(None, functools.partial(fn, *args, **kwargs))


class LibrespotBridge:
    """Manages librespot process lifecycle and non-blocking PCM audio relay for Tonal Speakers."""

    def __init__(
        self,
        binary_path: str = "/data/local/bin/librespot",
        cache_dir: str = "/data/local/librespot-cache",
        log_path: str = "/data/local/swonal-librespot.log",
        device_name: str = "Tonal Speakers",
        zeroconf_port: int = 41477,
        pcm_port: int = 41478,
        mock_mode: bool = False,
    ) -> None:
        self.binary_path = binary_path
        self.cache_dir = cache_dir
        self.log_path = log_path
        self.device_name = device_name
        self.zeroconf_port = zeroconf_port
        self.pcm_port = pcm_port
        self.mock_mode = mock_mode

        self.process: Optional[asyncio.subprocess.Process] = None
        self.pcm_server: Optional[asyncio.AbstractServer] = None
        self.clients: set[asyncio.StreamWriter] = set()
        self.authenticated_user: Optional[str] = None
        self.last_error: Optional[str] = None
        self.bytes_streamed: int = 0
        self.last_audio_ts: float = 0.0
        self.recent_logs: deque[str] = deque(maxlen=80)

        self._stdout_task: Optional[asyncio.Task] = None
        self._stderr_task: Optional[asyncio.Task] = None
        self._watchdog_task: Optional[asyncio.Task] = None
        self._restart_lock = asyncio.Lock()
        self._stopping = False
        self._auth_event = asyncio.Event()
        self._bound_wlan_ip: Optional[str] = None

    @property
    def credentials_path(self) -> Path:
        return Path(self.cache_dir) / "credentials.json"

    def has_cached_credentials(self) -> bool:
        try:
            return self.credentials_path.exists() and self.credentials_path.stat().st_size > 10
        except OSError:
            return False

    def get_cached_username(self) -> Optional[str]:
        if not self.has_cached_credentials():
            return None
        try:
            data = json.loads(self.credentials_path.read_text(encoding="utf-8"))
            return data.get("username")
        except Exception:
            return None

    @staticmethod
    def detect_wlan_ip() -> Optional[str]:
        try:
            out = subprocess.check_output(
                ["ip", "-4", "addr", "show", "wlan0"],
                stderr=subprocess.DEVNULL,
                timeout=2,
            ).decode("utf-8", errors="ignore")
            for line in out.splitlines():
                line = line.strip()
                if line.startswith("inet "):
                    return line.split()[1].split("/")[0]
        except Exception:
            pass
        return None

    def _configure_multicast_routing(self, wlan_ip: Optional[str]) -> None:
        if self.mock_mode or not wlan_ip:
            return
        try:
            subprocess.run(
                ["ip", "route", "replace", "224.0.0.0/4", "dev", "wlan0", "src", wlan_ip],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=2,
            )
            subprocess.run(
                ["ip", "rule", "del", "to", "224.0.0.0/4", "lookup", "main", "pref", "9000"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=2,
            )
            subprocess.run(
                ["ip", "rule", "add", "to", "224.0.0.0/4", "lookup", "main", "pref", "9000"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=2,
            )
        except Exception as exc:
            logger.debug("Multicast route setup skipped: %s", exc)

    async def start(self) -> None:
        """Start the local TCP PCM relay server and spawn librespot if binary is present."""
        self._stopping = False
        try:
            self.pcm_server = await asyncio.start_server(
                self._handle_pcm_client, host="127.0.0.1", port=self.pcm_port
            )
            logger.info("Librespot PCM audio relay listening on 127.0.0.1:%d", self.pcm_port)
        except Exception as exc:
            logger.warning("Could not bind PCM server on :%d: %s", self.pcm_port, exc)

        if self.mock_mode or not os.path.exists(self.binary_path):
            logger.info(
                "Librespot binary not at %s (mock_mode=%s); running in standby.",
                self.binary_path,
                self.mock_mode,
            )
            return

        await self.restart_process()
        self._watchdog_task = asyncio.create_task(self._watchdog_loop())

    async def stop(self) -> None:
        """Cleanly terminate librespot and close PCM client streams."""
        self._stopping = True
        if self._watchdog_task:
            self._watchdog_task.cancel()
            self._watchdog_task = None
        await self._kill_process()
        for writer in list(self.clients):
            try:
                writer.close()
            except Exception:
                pass
        self.clients.clear()
        if self.pcm_server:
            self.pcm_server.close()
            await self.pcm_server.wait_closed()
            self.pcm_server = None

    async def _handle_pcm_client(
        self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter
    ) -> None:
        peer = writer.get_extra_info("peername")
        logger.info("Android AudioTrack client connected to PCM bridge: %s", peer)
        self.clients.add(writer)
        try:
            while not self._stopping:
                data = await reader.read(256)
                if not data:
                    break
        except Exception:
            pass
        finally:
            self.clients.discard(writer)
            try:
                writer.close()
            except Exception:
                pass
            logger.info("Android AudioTrack client disconnected: %s", peer)

    async def _kill_process(self) -> None:
        for task in (self._stdout_task, self._stderr_task):
            if task:
                task.cancel()
        self._stdout_task = None
        self._stderr_task = None

        if self.process is not None:
            try:
                self.process.kill()
                await asyncio.wait_for(self.process.wait(), timeout=2.0)
            except Exception:
                pass
            self.process = None

        if not self.mock_mode:
            try:
                proc = await asyncio.create_subprocess_exec(
                    "pkill",
                    "-9",
                    "librespot",
                    stdout=asyncio.subprocess.DEVNULL,
                    stderr=asyncio.subprocess.DEVNULL,
                )
                await asyncio.wait_for(proc.wait(), timeout=2.0)
            except Exception:
                pass

    async def restart_process(
        self, access_token: Optional[str] = None, clear_credentials: bool = False
    ) -> dict:
        """Spawn or restart librespot with --backend pipe (serialized via lock)."""
        async with self._restart_lock:
            return await self._restart_process_unlocked(
                access_token=access_token, clear_credentials=clear_credentials
            )

    async def _restart_process_unlocked(
        self, access_token: Optional[str] = None, clear_credentials: bool = False
    ) -> dict:
        await self._kill_process()

        if clear_credentials:
            try:
                if self.credentials_path.exists():
                    self.credentials_path.unlink()
            except OSError:
                pass
            self.authenticated_user = None

        if self.mock_mode or not os.path.exists(self.binary_path):
            if access_token:
                self.authenticated_user = "mock-spotify-user"
            return await self.get_status_async()

        os.makedirs(self.cache_dir, exist_ok=True)
        wlan_ip = await _to_thread(self.detect_wlan_ip)
        self._bound_wlan_ip = wlan_ip
        await _to_thread(self._configure_multicast_routing, wlan_ip)

        cmd = [
            self.binary_path,
            "-v",
            "--name",
            self.device_name,
            "--device-type",
            "speaker",
            "--bitrate",
            "320",
            "--backend",
            "pipe",
            "--initial-volume",
            "90",
            "--zeroconf-port",
            str(self.zeroconf_port),
            "--cache",
            self.cache_dir,
            "--system-cache",
            self.cache_dir,
            "--cache-size-limit",
            "200M",
        ]
        if wlan_ip:
            cmd.extend(["--zeroconf-interface", wlan_ip])
        if access_token:
            cmd.extend(["-k", access_token])

        self._auth_event.clear()
        self.last_error = None

        env = os.environ.copy()
        env["PREFIX"] = "/data/data/com.termux/files/usr"
        env["LD_LIBRARY_PATH"] = "/data/data/com.termux/files/usr/lib"

        try:
            self.process = await asyncio.create_subprocess_exec(
                *cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                env=env,
            )
            self._stdout_task = asyncio.create_task(self._pump_stdout())
            self._stderr_task = asyncio.create_task(self._pump_stderr())
            logger.info("Spawned librespot (pid=%s, wlan_ip=%s)", self.process.pid, wlan_ip)
        except Exception as exc:
            self.last_error = str(exc)
            logger.error("Failed to spawn librespot: %s", exc)
            return self.get_status()

        if access_token or self.has_cached_credentials():
            try:
                await asyncio.wait_for(self._auth_event.wait(), timeout=4.5)
            except asyncio.TimeoutError:
                pass

        return self.get_status()

    async def _pump_stdout(self) -> None:
        """Read raw 44.1kHz S16LE stereo PCM from librespot stdout and relay to AudioTrack clients."""
        proc = self.process
        if not proc or not proc.stdout:
            return
        try:
            while not self._stopping:
                chunk = await proc.stdout.read(4096)
                if not chunk:
                    break
                self.bytes_streamed += len(chunk)
                self.last_audio_ts = time.time()
                if not self.clients:
                    continue
                stale: list[asyncio.StreamWriter] = []
                for writer in list(self.clients):
                    try:
                        writer.write(chunk)
                        if writer.transport.get_write_buffer_size() > 65536:
                            await asyncio.wait_for(writer.drain(), timeout=0.15)
                    except Exception:
                        stale.append(writer)
                for writer in stale:
                    self.clients.discard(writer)
                    try:
                        writer.close()
                    except Exception:
                        pass
        except asyncio.CancelledError:
            pass
        except Exception as exc:
            logger.debug("PCM stdout pump ended: %s", exc)

    async def _pump_stderr(self) -> None:
        """Parse librespot stderr logs to detect authentication, playback events, and credential errors."""
        proc = self.process
        if not proc or not proc.stderr:
            return
        log_file = None
        try:
            log_file = open(self.log_path, "a", encoding="utf-8")
        except Exception:
            pass

        try:
            while not self._stopping:
                raw_line = await proc.stderr.readline()
                if not raw_line:
                    break
                line = raw_line.decode("utf-8", errors="replace").rstrip()
                self.recent_logs.append(line)
                if log_file:
                    try:
                        log_file.write(line + "\n")
                        log_file.flush()
                    except Exception:
                        pass

                if "Authenticated as '" in line:
                    try:
                        user = line.split("Authenticated as '", 1)[1].split("'", 1)[0]
                        self.authenticated_user = user
                        self.last_error = None
                        logger.info("Librespot AP session authenticated as Spotify user: %s", user)
                    except Exception:
                        pass
                elif "is handled by dealer" in line:
                    self.last_error = None
                    self._auth_event.set()
                elif "INVALID_CREDENTIALS" in line or "LoginFailed" in line or "could not initialize spirc" in line:
                    self.last_error = "INVALID_CREDENTIALS"
                    self.authenticated_user = None
                    self._auth_event.set()
                    logger.warning(
                        "Librespot reported credential/spirc error (%s); purging stale credentials.json",
                        line,
                    )
                    try:
                        if self.credentials_path.exists():
                            self.credentials_path.unlink()
                    except OSError:
                        pass
        except asyncio.CancelledError:
            pass
        except Exception as exc:
            logger.debug("Stderr pump ended: %s", exc)
        finally:
            if log_file:
                try:
                    log_file.close()
                except Exception:
                    pass

    async def _watchdog_loop(self) -> None:
        """Ensure librespot stays running in Zeroconf/Connect mode and rebinds if wlan0 IP appears after boot."""
        try:
            while not self._stopping:
                await asyncio.sleep(5.0)
                if self._stopping or self.mock_mode or not os.path.exists(self.binary_path):
                    continue
                current_ip = await _to_thread(self.detect_wlan_ip)
                if self.process is None or self.process.returncode is not None:
                    logger.info("Librespot process exited; restarting in discovery/cached mode...")
                    await self.restart_process()
                elif current_ip and current_ip != self._bound_wlan_ip:
                    logger.info(
                        "WLAN IP changed (%s -> %s); restarting librespot to bind Zeroconf/mDNS...",
                        self._bound_wlan_ip,
                        current_ip,
                    )
                    await self.restart_process()
        except asyncio.CancelledError:
            pass

    def _query_zeroconf_info(self) -> Optional[dict]:
        if self.mock_mode:
            return {
                "status": 101,
                "remoteName": self.device_name,
                "activeUser": self.authenticated_user or "",
            }
        try:
            url = f"http://127.0.0.1:{self.zeroconf_port}/?action=getInfo"
            with urllib.request.urlopen(url, timeout=1.0) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except Exception:
            return None

    async def get_status_async(self) -> dict:
        """Non-blocking status query safe to call from the 30Hz motor safety event loop."""
        return await _to_thread(self.get_status)

    def get_status(self) -> dict:
        running = self.process is not None and self.process.returncode is None
        zc = self._query_zeroconf_info() if running or self.mock_mode else None
        active_user = (
            self.authenticated_user
            or (zc.get("activeUser") if isinstance(zc, dict) and zc.get("activeUser") else None)
            or self.get_cached_username()
        )
        return {
            "running": running or self.mock_mode,
            "pid": self.process.pid if running and self.process else None,
            "device_name": self.device_name,
            "has_cached_credentials": self.has_cached_credentials(),
            "authenticated_user": active_user,
            "last_error": self.last_error,
            "zeroconf_port": self.zeroconf_port,
            "pcm_stream_port": self.pcm_port,
            "active_audio_clients": len(self.clients),
            "bytes_streamed": self.bytes_streamed,
            "is_streaming_audio": (time.time() - self.last_audio_ts) < 2.0,
            "wlan_ip": self.detect_wlan_ip(),
            "zeroconf_info": zc,
        }

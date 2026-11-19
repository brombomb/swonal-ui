# Device Assets

This directory contains standalone precompiled assets deployed to the rooted Tonal V1 tablet (`arm64-v8a`, Android 6.0 Marshmallow):

- **`librespot-aarch64`**: Standalone statically-linked Spotify Connect speaker daemon (`librespot v0.8.0`) compiled with `aarch64-unknown-linux-musl` using:
  - Features: `--no-default-features --features 'rustls-tls-webpki-roots,rodio-backend,with-libmdns'`
  - Audio backend: Rodio + ALSA static (`libasound.a` built from `alsa-lib 1.2.14` with `--enable-static --disable-shared`)
  - No glibc dynamic dependency (`ld-linux-aarch64.so.1`), running directly under Android's kernel ALSA subsystem (`/dev/snd/pcmC0D0p`).
  - Cache: `--cache /data/local/librespot-cache` for persistent user session/token caching across boots.
- **`alsa/`**: Minimal ALSA configuration tree (`alsa.conf`, `cards/`, `ctl/`, `pcm/`) deployed to `/data/local/alsa` on device, referenced by `ALSA_CONFIG_DIR` and `ALSA_CONFIG_PATH` in `/data/adb/service.d/99-swonal-librespot.sh`.
- **DNS Resolver (`/system/etc/resolv.conf`)**: Android does not natively supply `/etc/resolv.conf`. The boot service script `/data/adb/service.d/99-swonal-librespot.sh` dynamically creates `/data/local/etc/resolv.conf` from `getprop net.dns1` and bind-mounts it over `/system/etc`, enabling musl libc to resolve Spotify AP servers (`apresolve.spotify.com`, `clienttoken.spotify.com`).

### Rebuilding librespot-aarch64

```bash
docker run --rm \
  -v /tmp/librespot-out:/output \
  ghcr.io/cross-rs/aarch64-unknown-linux-musl:edge bash -c '
set -e
export PATH=/x-tools/aarch64-unknown-linux-musl/bin:$PATH
curl -sSL https://www.alsa-project.org/files/pub/lib/alsa-lib-1.2.14.tar.bz2 | tar -xj
cd alsa-lib-1.2.14
./configure --host=aarch64-unknown-linux-musl --prefix=/x-tools/aarch64-unknown-linux-musl/aarch64-unknown-linux-musl/sysroot/usr --enable-static --disable-shared
make -j$(nproc) && make install
cd .. && rm -rf alsa-lib-1.2.14

curl --proto "=https" --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain stable --profile minimal --target aarch64-unknown-linux-musl
source $HOME/.cargo/env

export CC_aarch64_unknown_linux_musl=/x-tools/aarch64-unknown-linux-musl/bin/aarch64-unknown-linux-musl-gcc
export AR_aarch64_unknown_linux_musl=/x-tools/aarch64-unknown-linux-musl/bin/aarch64-unknown-linux-musl-ar
export CARGO_TARGET_AARCH64_UNKNOWN_LINUX_MUSL_LINKER=/x-tools/aarch64-unknown-linux-musl/bin/aarch64-unknown-linux-musl-gcc
export PKG_CONFIG_SYSROOT_DIR=/x-tools/aarch64-unknown-linux-musl/aarch64-unknown-linux-musl/sysroot
export PKG_CONFIG_PATH=/x-tools/aarch64-unknown-linux-musl/aarch64-unknown-linux-musl/sysroot/usr/lib/pkgconfig
export PKG_CONFIG_ALLOW_CROSS=1
export ALSA_STATIC=1

cargo install librespot --version 0.8.0 \
  --target aarch64-unknown-linux-musl \
  --no-default-features \
  --features "rustls-tls-webpki-roots,rodio-backend,with-libmdns" \
  --root /output

/x-tools/aarch64-unknown-linux-musl/bin/aarch64-unknown-linux-musl-strip /output/bin/librespot
'
```
